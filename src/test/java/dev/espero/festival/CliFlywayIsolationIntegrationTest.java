package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ensures CLI entry points cannot run migrations even when the environment asks for Flyway. */
@Testcontainers(disabledWithoutDocker = true)
class CliFlywayIsolationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void accountCliStructurallyExcludesFlywayWhenTheEnvironmentEnablesIt() throws Exception {
        try (ConfigurableApplicationContext context = start(AccountSettingsCliApplication.class, "account-settings-cli")) {
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(flywayHistoryTableExists()).isFalse();
        }
    }

    @Test
    void catalogCliStructurallyExcludesFlywayWhenTheEnvironmentEnablesIt() throws Exception {
        try (ConfigurableApplicationContext context = start(CatalogCliApplication.class, "catalog-cli")) {
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(flywayHistoryTableExists()).isFalse();
        }
    }

    private ConfigurableApplicationContext start(Class<?> application, String profile) {
        return new SpringApplicationBuilder(application)
            .profiles("db", profile)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.enabled=true"
            );
    }

    private boolean flywayHistoryTableExists() throws Exception {
        try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                 SELECT EXISTS (
                     SELECT 1
                     FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'
                 )
                 """)) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }
}
