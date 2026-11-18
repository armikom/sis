package com.armikom.sis.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests (the app is PostgreSQL-only). Import it in every {@code @SpringBootTest};
 * the container is shared across test contexts of one JVM.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    public PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
