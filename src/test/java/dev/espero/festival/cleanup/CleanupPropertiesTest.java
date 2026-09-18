package dev.espero.festival.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CleanupPropertiesTest {

    @Test
    void defaultsToAReadOnlyUnscheduledJobWithFiveHundredRowBatches() {
        CleanupProperties properties = new CleanupProperties();

        assertThat(properties.isScheduleEnabled()).isFalse();
        assertThat(properties.isDryRun()).isTrue();
        assertThat(properties.getBatchSize()).isEqualTo(500);
        assertThat(properties.hasDedicatedDataSourceAndRole()).isFalse();
    }

    @Test
    void rejectsBatchSizesThatCouldExceedThePerBatchSafetyBound() {
        CleanupProperties properties = new CleanupProperties();

        assertThatThrownBy(() -> properties.setBatchSize(501))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setBatchSize(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresEveryDedicatedDatasourceAndRoleFieldBeforeDeleteModeCanBeEnabled() {
        CleanupProperties properties = new CleanupProperties();
        CleanupProperties.CleanupDataSourceProperties datasource = properties.getDatasource();
        datasource.setUrl("jdbc:postgresql://cleanup-db/espero");
        datasource.setUsername("espero_cleanup");
        datasource.setPassword("secret");

        assertThat(properties.hasDedicatedDataSourceAndRole()).isFalse();

        datasource.setRole("espero_cleanup");

        assertThat(properties.hasDedicatedDataSourceAndRole()).isTrue();
    }
}
