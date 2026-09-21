package dev.espero.festival.preflight;

import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Release-only alias for the PostgreSQL 17 migration/preflight checks.
 *
 * <p>The inherited tests keep the existing focused coverage reusable while
 * giving the release profile one stable class name for Maven selection.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class Postgresql17MigrationReleaseTest extends DatabasePreflightPostgresql17IntegrationTest {
}
