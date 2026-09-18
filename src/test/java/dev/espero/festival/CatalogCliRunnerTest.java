package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class CatalogCliRunnerTest {

    private final CatalogRevisionService revisions = mock(CatalogRevisionService.class);
    private final CatalogCliRunner runner = new CatalogCliRunner(revisions);

    @Test
    void acceptsManifestOptionWithoutASecondPositionalArgument() {
        runner.run(new DefaultApplicationArguments(
            "import", "--manifest=C:/catalog/revision.json", "--actor=release-bot"
        ));

        verify(revisions).importManifest(Path.of("C:/catalog/revision.json"), "release-bot");
    }

    @Test
    void acceptsFestivalIdOverrideForImport() {
        UUID festivalId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        runner.run(new DefaultApplicationArguments(
            "import", "C:/catalog/revision.json", "--festival-id=" + festivalId
        ));

        verify(revisions).importManifest(Path.of("C:/catalog/revision.json"), "catalog-cli", festivalId);
    }

    @Test
    void rejectsInvalidFestivalIdBeforeCallingTheService() {
        DefaultApplicationArguments arguments = new DefaultApplicationArguments(
            "import", "C:/catalog/revision.json", "--festival-id=abc"
        );

        assertThatThrownBy(() -> runner.run(arguments))
            .isInstanceOf(CatalogCliException.class)
            .hasMessage("Option --festival-id must be a valid UUID.");
        verifyNoInteractions(revisions);
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
    void keepsPublishCommandBehavior() {
        UUID revision = UUID.fromString("22222222-2222-2222-2222-222222222222");

        runner.run(new DefaultApplicationArguments(
            "publish", "--revision=" + revision, "--actor=release-bot"
        ));

        verify(revisions).publish(revision, "release-bot");
    }
}
