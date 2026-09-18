package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class CatalogCliRunnerTest {

    private final CatalogRevisionService revisions = mock(CatalogRevisionService.class);
    private final CatalogExportService exports = mock(CatalogExportService.class);
    private final CatalogCliRunner runner = new CatalogCliRunner(revisions, exports);

    @TempDir
    private Path tempDir;

    @Test
    void acceptsManifestOptionWithoutASecondPositionalArgument() {
        runner.run(new DefaultApplicationArguments(
            "import", "--manifest=C:/catalog/revision.json", "--actor=release-bot"
        ));

        verify(revisions).importManifest(Path.of("C:/catalog/revision.json"), "release-bot");
    }

    @Test
    void acceptsRevisionOptionWithoutASecondPositionalArgument() {
        UUID revision = UUID.fromString("11111111-1111-1111-1111-111111111111");

        runner.run(new DefaultApplicationArguments(
            "validate", "--revision=" + revision, "--actor=release-bot"
        ));

        verify(revisions).validateRevision(revision, "release-bot");
    }

    @Test
    void requiresTheExpectedCurrentRevisionForARollback() {
        UUID source = UUID.fromString("11111111-1111-1111-1111-111111111111");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("rollback", source.toString())))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("--expected-current");
    }

    @Test
    void passesTheExpectedCurrentRevisionIncludingAnExplicitNone() {
        UUID source = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID current = UUID.fromString("22222222-2222-2222-2222-222222222222");

        runner.run(new DefaultApplicationArguments(
            "rollback", source.toString(), "--expected-current=" + current
        ));
        runner.run(new DefaultApplicationArguments(
            "rollback", source.toString(), "--expected-current=none"
        ));

        verify(revisions).rollback(source, current, "catalog-cli");
        verify(revisions).rollback(source, null, "catalog-cli");
    }

    @Test
    void writesTheExportedManifestAndReportsLegacyFindings() throws Exception {
        UUID revision = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Path output = tempDir.resolve("nested").resolve("revision.json");
        when(exports.export(revision)).thenReturn(new CatalogExportService.ExportResult(
            manifest(),
            List.of(CatalogExportService.LEGACY_FILTER_GROUPS_UNCONFIGURED + ": detail")
        ));

        runner.run(new DefaultApplicationArguments(
            "export", revision.toString(), "--out=" + output
        ));

        assertThat(Files.readString(output))
            .contains("\"festivalId\"")
            .contains("\"baselineRevisionId\"");
    }

    @Test
    void requiresAnOutputPathForAnExport() {
        UUID revision = UUID.fromString("33333333-3333-3333-3333-333333333333");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("export", revision.toString())))
            .isInstanceOf(CatalogCliException.class)
            .hasMessageContaining("--out");
    }

    private CatalogManifest manifest() {
        return new CatalogManifest(
            UUID.fromString("44444444-4444-4444-4444-444444444444"),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), null, List.of(), List.of(), List.of(),
            new CatalogManifest.TicketGuide(
                null, null, null, null, null, List.of(), null, null, null, null, null, null
            ),
            new CatalogManifest.StampGuide(
                "스탬프투어", List.of(), List.of(), "기념품", null, null, "수량 소진 시 종료", null
            )
        );
    }
}
