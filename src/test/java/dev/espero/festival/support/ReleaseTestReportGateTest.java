package dev.espero.festival.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseTestReportGateTest {
    private static final String GATE = "Postgresql17MigrationReleaseTest";

    @TempDir
    Path reports;

    @Test
    void acceptsOnlyAllRequiredReportsWithNoSkippedOrFailedTests() throws Exception {
        report(GATE, 2, 0, 0, 0);
        report("ExampleE2eTest", 3, 0, 0, 0);
        assertThatCode(() -> ReleaseTestReportGate.verify(reports, GATE + ",ExampleE2eTest"))
            .doesNotThrowAnyException();

        for (String attribute : new String[]{"skipped", "failures", "errors"}) {
            report("ExampleE2eTest", 3, attribute.equals("skipped") ? 1 : 0,
                attribute.equals("failures") ? 1 : 0, attribute.equals("errors") ? 1 : 0);
            assertThatThrownBy(() -> ReleaseTestReportGate.verify(reports, GATE + ",ExampleE2eTest"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not all pass");
        }
    }

    @Test
    void refusesMissingEmptyOrIncompleteMigrationEvidence() throws Exception {
        assertThatThrownBy(() -> ReleaseTestReportGate.verify(reports, GATE))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing");
        report(GATE, 0, 0, 0, 0);
        assertThatThrownBy(() -> ReleaseTestReportGate.verify(reports, GATE))
            .isInstanceOf(IllegalStateException.class);
        report(GATE, 1, 0, 0, 0);
        assertThatThrownBy(() -> ReleaseTestReportGate.verify(reports, GATE))
            .isInstanceOf(IllegalStateException.class);
        report(GATE, 2, 0, 0, 0);
        assertThatThrownBy(() -> ReleaseTestReportGate.verify(reports, GATE + ",MissingE2eTest"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing");
    }

    @Test
    void refusesPartialMethodSelectorsAndSelectionsWithoutTheMigrationGate() throws Exception {
        assertThatThrownBy(() -> ReleaseTestReportGate.verify(reports, GATE + "#freshOnly"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("full test classes");
        assertThatThrownBy(() -> ReleaseTestReportGate.verify(reports, "ExampleE2eTest"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(GATE);
    }

    private void report(String name, int tests, int skipped, int failures, int errors) throws Exception {
        Files.writeString(reports.resolve("TEST-fixture." + name + ".xml"), """
            <testsuite name="fixture.%s" tests="%d" skipped="%d" failures="%d" errors="%d"/>
            """.formatted(name, tests, skipped, failures, errors));
    }
}
