package com.yulinlin.riskapp.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yulinlin.data.core.YulinlinCoreAutoConfig;
import com.yulinlin.data.core.loadbalan.RandomLoadBalance;
import com.yulinlin.data.core.request.ExecuteRequest;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.jdbc.h2.H2SchemaManager;
import com.yulinlin.riskapp.persistence.RiskOrmConfiguration;
import com.yulinlin.riskapp.schema.model.RiskTables;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class RiskSchemaInitializerTest {
    private final DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate db = new JdbcTemplate(source);

    @AfterEach void cleanup() {
        db.execute("DROP ALL OBJECTS");
    }

    @Test void newDatabaseCreatesAllTablesAndEnforcesDataIntegrity() {
        initialize();
        assertThat(db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'",
                String.class)).containsExactlyInAnyOrder("RISK_PROJECT", "RISK_EVENT", "RISK_BLACKLIST",
                "RISK_GLOBAL_BATCH", "RISK_GLOBAL_SUMMARY", "RISK_GLOBAL_STATE", "RISK_GLOBAL_SAMPLE",
                "RISK_GLOBAL_SAMPLE_BUDGET", "RISK_GLOBAL_EVENT", "RISK_ADMIN_ACCOUNT");
        seedData();

        assertThat(db.queryForObject("SELECT blacklist_version FROM risk_project WHERE id='p1'", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT id FROM risk_event WHERE event_id='event-1'", Long.class)).isPositive();
        assertThat(db.queryForObject("SELECT id FROM risk_global_sample WHERE event_id='sample-1'", Long.class)).isPositive();
        assertThat(db.queryForObject("SELECT resource_id FROM risk_global_sample WHERE event_id='sample-1'", String.class)).isNull();

        assertThatThrownBy(() -> project("duplicate-key", "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> event("event-1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('p1','u','duplicate',2000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> batch("p1", "batch-1")).isInstanceOf(DataIntegrityViolationException.class);
        // The same external identifiers remain legal in a different project.
        batch("p2", "batch-1");
        assertThatThrownBy(() -> db.update("INSERT INTO risk_global_summary(project_id,user_id,bucket_at,request_count,failures,denied,suspected,details_json) "
                + "VALUES('p1','u',1000,2,1,1,FALSE,'{}')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO risk_global_state(project_id,user_id,updated_at,state_json) VALUES('p1','u',2000,'{}')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('missing','u','orphan',2000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO risk_global_sample_budget(minute_at) VALUES(2000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(indexNames()).contains("IDX_RISK_EVENT_RETENTION", "IDX_RISK_EVENT_PROJECT_TIME",
                "IDX_RISK_EVENT_USER_HISTORY", "IDX_RISK_EVENT_USER_ONLINE", "IDX_GLOBAL_BATCH_EXPIRY",
                "IDX_GLOBAL_SUMMARY_PROJECT_TIME", "IDX_GLOBAL_SUMMARY_EXPIRY", "IDX_GLOBAL_STATE_EXPIRY",
                "IDX_GLOBAL_SAMPLE_USER_TIME", "IDX_GLOBAL_SAMPLE_EXPIRY", "IDX_GLOBAL_EVENT_PROJECT_TIME",
                "IDX_GLOBAL_EVENT_USER_TIME", "IDX_GLOBAL_EVENT_EXPIRY");
        assertThat(indexColumns("IDX_GLOBAL_EVENT_PROJECT_TIME"))
                .containsExactly("PROJECT_ID:ASC", "OCCURRED_AT:DESC", "ID:DESC");
        assertThat(indexColumns("IDX_GLOBAL_EVENT_USER_TIME"))
                .containsExactly("PROJECT_ID:ASC", "USER_ID:ASC", "OCCURRED_AT:DESC", "ID:DESC");
        assertThat(indexColumns("IDX_GLOBAL_EVENT_EXPIRY"))
                .containsExactly("OCCURRED_AT:ASC", "ID:ASC");
    }

    @Test void completeEventTableSupportsOrmBatchesNullableFieldsAndRequiredConstraints() {
        initialize();
        project("p1", "a".repeat(64));
        var session = RiskOrmConfiguration.createSession(source);
        try {
            var orm = RouteSession.builder().build();
            orm.setLoadBalance(new RandomLoadBalance());
            orm.setWrapperFactory(new YulinlinCoreAutoConfig().wrapperFactory());
            orm.registerSession(session);
            var write = ExecuteRequest.ofInsert(RiskTables.GlobalEvent.class);
            for (int i = 0; i < 200; i++) {
                var event = new RiskTables.GlobalEvent();
                event.projectId = "p1";
                // Cross-batch event IDs are not unique in the existing ingestion contract.
                event.eventId = "reused-external-id";
                event.userId = "u" + i;
                event.action = "access";
                event.resourceId = i % 2 == 0 ? "video:" + i : null;
                event.ip = i % 2 == 0 ? "2001:db8::1" : null;
                event.route = "/videos/" + i;
                event.occurredAt = 1000L + i;
                write.addRequest(orm.getWrapperFactory().createInsertWrapper(event));
            }
            Integer inserted = orm.transaction(() -> orm.insert(write));
            assertThat(inserted).isEqualTo(200);
        } finally {
            session.shutdown();
        }
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT id) FROM risk_global_event", Long.class)).isEqualTo(200);
        assertThat(db.queryForObject("SELECT MIN(id) FROM risk_global_event", Long.class)).isPositive();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event WHERE resource_id IS NULL AND ip IS NULL", Long.class)).isEqualTo(100);
        assertThat(db.queryForObject("SELECT ip FROM risk_global_event WHERE user_id='u0'", String.class)).isEqualTo("2001:db8::1");
        assertThatThrownBy(() -> db.update("UPDATE risk_global_event SET project_id='missing' WHERE user_id='u0'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("UPDATE risk_global_event SET user_id=NULL WHERE user_id='u0'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("UPDATE risk_global_event SET route=NULL WHERE user_id='u0'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("UPDATE risk_global_event SET occurred_at=NULL WHERE user_id='u0'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        long previousId = db.queryForObject("SELECT MAX(id) FROM risk_global_event", Long.class);
        var indexes = indexNames();
        initialize();
        assertThat(indexNames()).containsExactlyElementsOf(indexes);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class)).isEqualTo(200);
        assertThat(globalEvent("after-restart")).isGreaterThan(previousId);
    }

    @Test void repeatedInitializationPreservesRowsIndexesAndIdentityCounters() {
        initialize();
        seedData();
        db.update("UPDATE risk_project SET blacklist_version=72 WHERE id='p1'");
        long previousEvent = event("event-2");
        long previousSample = sample("sample-2");
        var indexes = indexNames();
        var counts = counts();

        initialize();

        assertThat(indexNames()).containsExactlyElementsOf(indexes);
        assertThat(counts()).isEqualTo(counts);
        assertThat(db.queryForObject("SELECT blacklist_version FROM risk_project WHERE id='p1'", Long.class)).isEqualTo(72);
        assertThat(event("event-3")).isGreaterThan(previousEvent);
        assertThat(sample("sample-3")).isGreaterThan(previousSample);
        assertStoredEvidence();
    }

    @Test void legacyDatabaseKeepsExistingDataConstraintsAndIndexNames() {
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql"),
                new ClassPathResource("legacy-schema-global.sql")).execute(source);
        seedData();
        db.update("UPDATE risk_project SET blacklist_version=91 WHERE id='p1'");
        long previousEvent = event("event-2");
        long previousSample = sample("sample-2");
        var indexes = indexNames();
        var counts = counts();

        initialize();
        initialize();

        assertLegacyIndexesAndNewEventIndexes(indexes);
        assertThat(counts()).isEqualTo(counts);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT blacklist_version FROM risk_project WHERE id='p1'", Long.class)).isEqualTo(91);
        assertThat(db.queryForObject("SELECT api_key FROM risk_project WHERE id='p1'", String.class)).isNull();
        assertThat(db.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema='PUBLIC' AND table_name='RISK_PROJECT' AND column_name='API_KEY'", Long.class))
                .isEqualTo(43);
        assertThat(db.queryForObject("SELECT key_digest FROM risk_project WHERE id='p1'", String.class)).isEqualTo("a".repeat(64));
        assertThat(event("event-3")).isGreaterThan(previousEvent);
        assertThat(sample("sample-3")).isGreaterThan(previousSample);
        assertStoredEvidence();
        assertThatThrownBy(() -> batch("p1", "batch-1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('missing','u','orphan',2000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void legacySamplesGainNullableIpWithoutLosingRowsOrResettingIdentity() {
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql"),
                new ClassPathResource("legacy-schema-global.sql")).execute(source);
        seedData();
        long previousSample = sample("sample-2");
        var indexes = indexNames();
        var sampleIndexes = sampleIndexDefinitions();
        var counts = counts();

        initialize();

        assertThat(db.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema='PUBLIC' AND table_name='RISK_GLOBAL_SAMPLE' AND column_name='IP'
                """, String.class)).isEqualTo("YES");
        assertThat(db.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema='PUBLIC' AND table_name='RISK_GLOBAL_SAMPLE' AND column_name='IP'
                """, Long.class)).isEqualTo(45);
        assertThat(db.queryForObject("SELECT ip FROM risk_global_sample WHERE event_id='sample-2'", String.class)).isNull();
        assertThat(db.queryForObject("SELECT id FROM risk_global_sample WHERE event_id='sample-2'", Long.class)).isEqualTo(previousSample);
        assertThat(counts()).isEqualTo(counts);
        assertLegacyIndexesAndNewEventIndexes(indexes);
        assertThat(sampleIndexDefinitions()).containsExactlyElementsOf(sampleIndexes);
        assertStoredEvidence();

        db.update("UPDATE risk_global_sample SET ip=? WHERE event_id='sample-2'", "2001:db8::123");
        initialize();

        assertThat(db.queryForObject("SELECT ip FROM risk_global_sample WHERE event_id='sample-2'", String.class)).isEqualTo("2001:db8::123");
        assertThatThrownBy(() -> db.update("UPDATE risk_global_sample SET id=? WHERE event_id='sample-1'", previousSample))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("UPDATE risk_global_sample SET project_id='missing' WHERE event_id='sample-2'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("UPDATE risk_global_sample SET user_id=NULL WHERE event_id='sample-2'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sample("sample-after-ip-upgrade")).isGreaterThan(previousSample);
        assertThat(db.queryForObject("SELECT ip FROM risk_global_sample WHERE event_id='sample-after-ip-upgrade'", String.class)).isNull();
        assertLegacyIndexesAndNewEventIndexes(indexes);
        assertThat(sampleIndexDefinitions()).containsExactlyElementsOf(sampleIndexes);
    }

    @Test void interruptedInitializationCanFinishConstraintsWithoutLosingRows() {
        var orm = new H2SchemaManager();
        for (Class<?> table : List.of(RiskTables.Project.class, RiskTables.Event.class, RiskTables.Blacklist.class)) {
            orm.createTableSql(table, false).forEach(db::execute);
        }
        db.update("INSERT INTO risk_project(id,name,key_digest,created_at,blacklist_version) VALUES('p1','项目 A',?,1000,0)", "a".repeat(64));
        db.update("INSERT INTO risk_event(id,project_id,event_id,user_id,behavior_type,resource_id,occurred_at,received_at,verdict_status) "
                + "VALUES(77,'p1','before-interruption','u','video-play-url','v',1000,1000,'NOT_MATCHED')");

        initialize();
        initialize();

        assertThat(db.queryForObject("SELECT id FROM risk_event WHERE event_id='before-interruption'", Long.class)).isEqualTo(77);
        assertThat(event("after-interruption")).isGreaterThan(77);
        db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('p1','u','reason',1000)");
        assertThatThrownBy(() -> db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('p1','u','duplicate',1000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('missing','u','orphan',1000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        project("p2", "b".repeat(64));
        assertThat(db.queryForObject("SELECT blacklist_version FROM risk_project WHERE id='p2'", Long.class)).isZero();
    }

    @Test void incompatibleExistingPrimaryKeyStopsStartupWithoutReplacingIt() {
        db.execute("CREATE TABLE risk_blacklist(project_id VARCHAR(36) NOT NULL, user_id VARCHAR(128) PRIMARY KEY, "
                + "reason VARCHAR(500) NOT NULL, added_at BIGINT NOT NULL)");
        db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('p1','u','keep',1000)");

        assertThatThrownBy(this::initialize).hasRootCauseMessage("Unexpected primary key on RISK_BLACKLIST: [USER_ID]; "
                + "expected [PROJECT_ID, USER_ID]. Migrate explicitly; existing keys are never replaced.");

        assertThat(db.queryForObject("SELECT reason FROM risk_blacklist WHERE user_id='u'", String.class)).isEqualTo("keep");
        assertThatThrownBy(() -> db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('p2','u','duplicate',1000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void initialize() {
        assertThat(new RiskSchemaInitializer(source).initializeDatabase()).isTrue();
    }

    private void seedData() {
        project("p1", "a".repeat(64));
        project("p2", "b".repeat(64));
        event("event-1");
        sample("sample-1");
        db.update("INSERT INTO risk_blacklist(project_id,user_id,reason,added_at) VALUES('p1','u','人工确认',1000)");
        batch("p1", "batch-1");
        db.update("INSERT INTO risk_global_summary(project_id,user_id,bucket_at,request_count,failures,denied,suspected,details_json) "
                + "VALUES('p1','u',1000,2,1,1,FALSE,'{\"evidence\":\"saved\"}')");
        db.update("INSERT INTO risk_global_state(project_id,user_id,updated_at,state_json) VALUES('p1','u',1000,'{\"count\":2}')");
        db.update("INSERT INTO risk_global_sample_budget(minute_at,sample_count) VALUES(1000,1)");
    }

    private void project(String id, String digest) {
        db.update("INSERT INTO risk_project(id,name,key_digest,created_at) VALUES(?,?,?,1000)", id, "项目 " + id, digest);
    }

    private long event(String id) {
        db.update("INSERT INTO risk_event(project_id,event_id,user_id,behavior_type,resource_id,occurred_at,received_at,verdict_status) "
                + "VALUES('p1',?,'u','video-play-url','v',1000,1000,'NOT_MATCHED')", id);
        return db.queryForObject("SELECT id FROM risk_event WHERE project_id='p1' AND event_id=?", Long.class, id);
    }

    private long sample(String id) {
        db.update("INSERT INTO risk_global_sample(project_id,user_id,event_id,action,route,method,occurred_at,duration_ms,response_status,sample_kind) "
                + "VALUES('p1','u',?,'VIEW','/items/{id}','GET',1000,12,200,'NORMAL')", id);
        return db.queryForObject("SELECT id FROM risk_global_sample WHERE event_id=?", Long.class, id);
    }

    private void batch(String projectId, String batchId) {
        db.update("INSERT INTO risk_global_batch(project_id,batch_id,payload_digest,accepted,created_at) VALUES(?,?,?,2,1000)",
                projectId, batchId, "d".repeat(64));
    }

    private long globalEvent(String eventId) {
        db.update("INSERT INTO risk_global_event(project_id,event_id,user_id,action,route,occurred_at) "
                + "VALUES('p1',?,'u','access','/videos/1',1000)", eventId);
        return db.queryForObject("SELECT id FROM risk_global_event WHERE event_id=?", Long.class, eventId);
    }

    private void assertLegacyIndexesAndNewEventIndexes(List<String> legacyIndexes) {
        var actual = indexNames();
        var added = List.of("IDX_GLOBAL_EVENT_PROJECT_TIME", "IDX_GLOBAL_EVENT_USER_TIME", "IDX_GLOBAL_EVENT_EXPIRY");
        assertThat(actual).containsAll(added);
        assertThat(actual.stream().filter(name -> !added.contains(name)).toList()).containsExactlyElementsOf(legacyIndexes);
    }

    private List<String> indexNames() {
        // H2 may rename generated constraint indexes when adding a column; explicit business names stay stable.
        return db.queryForList("SELECT index_name FROM information_schema.indexes WHERE table_schema='PUBLIC' AND is_generated=FALSE ORDER BY index_name", String.class);
    }

    private List<String> indexColumns(String name) {
        return db.queryForList("""
                SELECT column_name || ':' || ordering_specification
                FROM information_schema.index_columns
                WHERE table_schema='PUBLIC' AND index_name=? ORDER BY ordinal_position
                """, String.class, name);
    }

    private List<String> sampleIndexDefinitions() {
        // Compare every sample index, including generated ones, by ordered columns and uniqueness rather than name.
        return db.queryForList("""
                SELECT LISTAGG(column_name || ':' || ordering_specification || ':' || is_unique, ',')
                       WITHIN GROUP (ORDER BY ordinal_position)
                FROM information_schema.index_columns
                WHERE table_schema='PUBLIC' AND table_name='RISK_GLOBAL_SAMPLE'
                GROUP BY index_name ORDER BY 1
                """, String.class);
    }

    private Map<String, Long> counts() {
        var counts = new java.util.LinkedHashMap<String, Long>();
        for (String table : List.of("risk_project", "risk_event", "risk_blacklist", "risk_global_batch",
                "risk_global_summary", "risk_global_state", "risk_global_sample", "risk_global_sample_budget")) {
            counts.put(table, db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private void assertStoredEvidence() {
        assertThat(db.queryForObject("SELECT reason FROM risk_blacklist WHERE project_id='p1' AND user_id='u'", String.class)).isEqualTo("人工确认");
        assertThat(db.queryForObject("SELECT details_json FROM risk_global_summary WHERE project_id='p1' AND user_id='u'", String.class))
                .isEqualTo("{\"evidence\":\"saved\"}");
        assertThat(db.queryForObject("SELECT state_json FROM risk_global_state WHERE project_id='p1' AND user_id='u'", String.class))
                .isEqualTo("{\"count\":2}");
        assertThat(db.queryForObject("SELECT accepted FROM risk_global_batch WHERE project_id='p1' AND batch_id='batch-1'", Integer.class)).isEqualTo(2);
    }
}
