package com.yulinlin.riskapp.schema;

import com.yulinlin.data.core.schema.SchemaEntityScanner;
import com.yulinlin.data.core.schema.SchemaMode;
import com.yulinlin.jdbc.h2.H2SchemaManager;
import com.yulinlin.riskapp.schema.model.RiskTables;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.stereotype.Component;

/** Generates table DDL from ORM models using the same pool as the risk writer. */
@Component
public final class RiskSchemaInitializer extends DataSourceScriptDatabaseInitializer {
    private static final Logger LOG = LoggerFactory.getLogger(RiskSchemaInitializer.class);
    private final DataSource source;

    public RiskSchemaInitializer(DataSource source) {
        super(source, settings());
        this.source = source;
    }

    private static DatabaseInitializationSettings settings() {
        var settings = new DatabaseInitializationSettings();
        settings.setMode(DatabaseInitializationMode.ALWAYS);
        settings.setSchemaLocations(List.of("classpath:schema-indexes.sql"));
        return settings;
    }

    @Override protected void runScripts(Scripts scripts) {
        var schemas = new H2SchemaManager();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            if (!"H2".equals(connection.getMetaData().getDatabaseProductName())) {
                throw new IllegalStateException("风控服务的自动建表目前仅支持 H2");
            }
            for (Class<?> entity : SchemaEntityScanner.scan(List.of("com.yulinlin.riskapp.schema.model"))) {
                // Existing SQL uses H2's uppercase identifiers. Explicit mappings must not be converted.
                for (String sql : schemas.createTableSql(entity, false)) {
                    LOG.info("[risk][schema] {}", sql);
                    statement.execute(sql);
                }
            }
            // Add optional fields to existing tables without changing their column types or data.
            for (var entity : List.of(RiskTables.GlobalSample.class, RiskTables.Project.class)) {
                schemas.ensureTable(connection, entity, false, SchemaMode.CREATE, sql -> {
                    LOG.info("[risk][schema] {}", sql);
                    statement.execute(sql);
                });
            }
            // The ORM does not describe composite keys, identities, nullability, defaults or foreign keys.
            RiskSchemaConstraints.initialize(connection);
        } catch (SQLException error) {
            throw new IllegalStateException("风控数据库初始化失败", error);
        }
        // Keep established index names to avoid duplicate indexes when opening an existing database.
        super.runScripts(scripts);
    }
}
