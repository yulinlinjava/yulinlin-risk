package com.yulinlin.riskapp.persistence;

import com.yulinlin.data.core.proxy.EntityProxyService;
import com.yulinlin.jdbc.JdbcSessionProperties;
import com.yulinlin.jdbc.h2.H2Session;
import com.yulinlin.jdbc.h2.coder.H2CoderManager;
import com.yulinlin.jdbc.session.JdbcSession;
import com.yulinlin.jdbc.session.JdbcSessionFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the existing Spring data source with the ORM's routed CRUD and transaction support. */
@Configuration(proxyBeanMethods = false)
public class RiskOrmConfiguration {
    public static final String GROUP = "risk";

    @Bean
    public JdbcSessionFactory riskJdbcSessionFactory() {
        return sessionFactory();
    }

    @Bean(destroyMethod = "shutdown")
    @DependsOnDatabaseInitialization
    public JdbcSession riskSession(DataSource source,
            @Qualifier("riskJdbcSessionFactory") JdbcSessionFactory factory) {
        return synchronousSession(factory.create(source, GROUP));
    }

    /** Standalone session for isolated database tests; does not register a global SessionUtil. */
    public static JdbcSession createSession(DataSource source) {
        JdbcSession session = synchronousSession(sessionFactory().create(source, GROUP));
        // Outside Spring the factory's normal autowired proxy service is not supplied.
        session.setProxyService(EntityProxyService.newInstance());
        return session;
    }

    private static JdbcSessionFactory sessionFactory() {
        var properties = new JdbcSessionProperties();
        // The existing H2 tables and JoinField mappings explicitly use uppercase identifiers.
        properties.setMapUnderscoreToCamelCase(false);
        properties.setLog(false);
        properties.setExecuteBatchSize(128);
        properties.setParallelConnections(1);
        return new JdbcSessionFactory("jdbc:h2:", H2Session::new, properties, new H2CoderManager());
    }

    private static JdbcSession synchronousSession(JdbcSession session) {
        // The service owns its bounded writer. ORM batches run on that thread and its transaction.
        // Release the unused executor; the shared DataSource remains owned by Spring/the test.
        session.getThreadPoolExecutor().shutdown();
        session.setThreadPoolExecutor(null);
        return session;
    }
}
