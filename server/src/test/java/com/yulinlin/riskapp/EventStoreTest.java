package com.yulinlin.riskapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulinlin.data.core.YulinlinCoreAutoConfig;
import com.yulinlin.data.core.loadbalan.RandomLoadBalance;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.jdbc.session.JdbcSession;
import com.yulinlin.riskapp.EventStore.Event;
import com.yulinlin.riskapp.EventStore.Insert;
import com.yulinlin.riskapp.EventStore.Stored;
import com.yulinlin.riskapp.persistence.RiskOrmConfiguration;
import com.yulinlin.riskapp.schema.RiskSchemaInitializer;
import com.yulinlin.security.risk.RiskModels.Match;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class EventStoreTest {
    private static final String PROJECT = "project-a";
    private static final long AT = 1_794_398_400_000L;
    private JdbcTemplate db;
    private JdbcSession session;
    private EventStore store;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:event-store-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new RiskSchemaInitializer(source).initializeDatabase();
        db = new JdbcTemplate(source);
        session = RiskOrmConfiguration.createSession(source);
        var orm = RouteSession.builder().build();
        orm.setLoadBalance(new RandomLoadBalance());
        orm.setWrapperFactory(new YulinlinCoreAutoConfig().wrapperFactory());
        orm.registerSession(session);
        store = new EventStore(orm);
        db.update("INSERT INTO risk_project(id,name,key_digest,created_at) VALUES(?,?,?,?)",
                PROJECT, "项目 A", "a".repeat(64), AT);
    }

    @AfterEach void teardown() {
        db.execute("DROP ALL OBJECTS");
        session.shutdown();
    }

    @Test void readsLegacyMatchJsonWithoutChangingStringIdsOrLongStatistics() {
        String legacy = """
                [{"componentId":"0007","ruleId":"0012","windowStart":1794398399000,
                  "windowEnd":1794398400000,"reason":"历史命中 #{userId}",
                  "statistics":{"00123":4294967296,"count":9007199254740993}}]
                """;
        insertLegacy("0001", "SUSPECTED", legacy);

        var stored = store.existing(PROJECT, List.of("0001")).get("0001");
        assertThat(stored.event().userId()).isEqualTo("0002");
        assertThat(stored.event().resourceId()).isEqualTo("0003");
        assertThat(stored.matches()).containsExactly(new Match("0007", "0012", 1_794_398_399_000L,
                AT, "历史命中 #{userId}", Map.of("00123", 4_294_967_296L, "count", 9_007_199_254_740_993L)));
        assertThat(store.result(stored, true)).isEqualTo(new EventStore.Result("SUSPECTED", stored.matches(), AT, true));
    }

    @Test void writesMatchListsAsTheExistingJsonArrayInAClob() throws Exception {
        var matches = List.of(new Match("0007", "0012", AT - 1000, AT,
                "命中 ' #{userId}", Map.of("00123", 9_007_199_254_740_993L)));
        var stored = new Stored(new Event("0001", "0002", "PLAY", "0003", AT), AT, "SUSPECTED", matches);
        store.insert(List.of(new Insert(PROJECT, stored)));

        assertThat(store.existing(PROJECT, List.of("0001")).get("0001")).isEqualTo(stored);
        String persisted = db.queryForObject("SELECT matches_json FROM risk_event WHERE event_id=?", String.class, "0001");
        var tree = new ObjectMapper().readTree(persisted);
        assertThat(tree.isArray()).isTrue();
        assertThat(tree.get(0).path("componentId").textValue()).isEqualTo("0007");
        assertThat(tree.get(0).path("statistics").path("00123").longValue()).isEqualTo(9_007_199_254_740_993L);
        assertThat(db.queryForObject("""
                SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME='RISK_EVENT' AND COLUMN_NAME='MATCHES_JSON'
                """, String.class)).isEqualTo("CHARACTER LARGE OBJECT");
    }

    @Test void preservesNullStorageAndEmptyMatchesForLegacyAndNewEvents() {
        insertLegacy("legacy-null", EventStore.HISTORY_ONLY, null);
        var stored = new Stored(new Event("new-empty", "0002", "PLAY", "0003", AT), AT, "NORMAL", List.of());
        store.insert(List.of(new Insert(PROJECT, stored)));

        var found = store.existing(PROJECT, List.of("legacy-null", "new-empty"));
        assertThat(found.get("legacy-null").matches()).isEmpty();
        assertThat(store.result(found.get("legacy-null"), false).judgedAt()).isNull();
        assertThat(found.get("new-empty")).isEqualTo(stored);
        assertThat(db.queryForObject("SELECT matches_json FROM risk_event WHERE event_id=?", String.class, "new-empty")).isNull();
    }

    private void insertLegacy(String eventId, String status, String matches) {
        db.update("""
                INSERT INTO risk_event(project_id,event_id,user_id,behavior_type,resource_id,
                                       occurred_at,received_at,verdict_status,matches_json)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, PROJECT, eventId, "0002", "PLAY", "0003", AT, AT, status, matches);
    }
}
