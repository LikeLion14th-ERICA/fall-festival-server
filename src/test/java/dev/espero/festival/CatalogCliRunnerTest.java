package dev.espero.festival;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    void acceptsRevisionOptionWithoutASecondPositionalArgument() {
        UUID revision = UUID.fromString("11111111-1111-1111-1111-111111111111");

        runner.run(new DefaultApplicationArguments(
            "validate", "--revision=" + revision, "--actor=release-bot"
        ));

        verify(revisions).validateRevision(revision, "release-bot");
    }
}
