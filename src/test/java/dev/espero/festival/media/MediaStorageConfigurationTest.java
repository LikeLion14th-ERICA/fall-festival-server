package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.cleanup.GoodsDetachedMediaCleanupTarget;
import dev.espero.festival.cleanup.GoodsUnattachedMediaCleanupTarget;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaStorageConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(MediaStorageConfiguration.class);

    @TempDir
    Path temporaryDirectory;

    @Test
    void storageRootIsOptionalUntilMediaUploadIsEnabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MediaStorage.class);
            assertThat(context).doesNotHaveBean(GoodsImageInspector.class);
            assertThat(context).doesNotHaveBean(GoodsImageProcessor.class);
            assertThat(context).doesNotHaveBean(GoodsUnattachedMediaCleanupTarget.class);
            assertThat(context).doesNotHaveBean(GoodsDetachedMediaCleanupTarget.class);
        });
    }

    @Test
    void configuredRootCreatesTheFilesystemStorageBean() {
        contextRunner
            .withPropertyValues("festival.media.storage-root=" + temporaryDirectory)
            .run(context -> {
                assertThat(context).hasSingleBean(MediaStorage.class);
                assertThat(context).hasSingleBean(GoodsImageInspector.class);
                assertThat(context).hasSingleBean(GoodsImageProcessor.class);
                assertThat(context).doesNotHaveBean(GoodsUnattachedMediaCleanupTarget.class);
                assertThat(context).doesNotHaveBean(GoodsDetachedMediaCleanupTarget.class);
            });
    }

    @Test
    void dbProfileAndConfiguredStorageRegisterBothCleanupTargets() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=db",
                "festival.media.storage-root=" + temporaryDirectory
            )
            .run(context -> {
                assertThat(context).hasSingleBean(GoodsUnattachedMediaCleanupTarget.class);
                assertThat(context).hasSingleBean(GoodsDetachedMediaCleanupTarget.class);
            });
    }
}
