package dev.espero.festival.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CleanupJobSafetyTest {

    private static final Instant NOW = Instant.parse("2030-09-29T03:00:00Z");

    @Test
    void refusesDestructiveRunsWithoutTheDedicatedDatasourceAndRole() {
        CleanupProperties properties = new CleanupProperties();
        properties.setDryRun(false);
        CleanupDataSourceProvider dataSourceProvider = mock(CleanupDataSourceProvider.class);
        when(dataSourceProvider.hasDedicatedDataSourceAndRole()).thenReturn(false);
        CleanupTarget target = mock(CleanupTarget.class);
        CleanupJob job = new CleanupJob(
            properties,
            dataSourceProvider,
            List.of(target),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        CleanupRunResult result = job.run(CleanupMode.DELETE);

        assertThat(result.status()).isEqualTo(CleanupRunStatus.SKIPPED_UNSAFE_CONFIGURATION);
        assertThat(result.deletedCount()).isZero();
        verify(dataSourceProvider, never()).dataSource();
        verify(target, never()).run(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotScheduleWhenTheScheduleFlagIsOff() {
        CleanupProperties properties = new CleanupProperties();
        CleanupDataSourceProvider dataSourceProvider = mock(CleanupDataSourceProvider.class);
        CleanupJob job = new CleanupJob(
            properties,
            dataSourceProvider,
            List.of(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        CleanupRunResult result = job.runScheduled();

        assertThat(result.status()).isEqualTo(CleanupRunStatus.SKIPPED_DISABLED);
        verify(dataSourceProvider, never()).dataSource();
    }

    @Test
    void doesNotLogConnectionFailureDetails(CapturedOutput output) throws Exception {
        CleanupProperties properties = new CleanupProperties();
        CleanupDataSourceProvider dataSourceProvider = mock(CleanupDataSourceProvider.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSourceProvider.dataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenThrow(new SQLException("jdbc:postgresql://user:secret@db.invalid/festival"));
        CleanupJob job = new CleanupJob(
            properties,
            dataSourceProvider,
            List.of(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> job.run(CleanupMode.DRY_RUN)).isInstanceOf(RuntimeException.class);

        assertThat(output)
            .contains("cleanup_run_failed mode=DRY_RUN error_type=")
            .doesNotContain("jdbc:postgresql://user:secret@db.invalid/festival");
    }
}
