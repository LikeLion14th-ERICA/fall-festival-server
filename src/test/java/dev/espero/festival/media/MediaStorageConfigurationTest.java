package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;

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
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(MediaStorage.class));
    }

    @Test
    void configuredRootCreatesTheFilesystemStorageBean() {
        contextRunner
            .withPropertyValues("festival.media.storage-root=" + temporaryDirectory)
            .run(context -> assertThat(context).hasSingleBean(MediaStorage.class));
    }
}
