package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemMediaStorageTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEDIA_ID = UUID.fromString("abcdef01-2222-3333-4444-555555555555");

    @TempDir
    Path temporaryDirectory;

    @Test
    void derivesDeterministicContainedPathsFromUuidsOnly() throws Exception {
        FileSystemMediaStorage storage = storage();
        UUID operationId = UUID.randomUUID();

        assertThat(storage.storageKey(FESTIVAL_ID, MEDIA_ID))
            .isEqualTo("goods/11111111-1111-1111-1111-111111111111/ab/abcdef01-2222-3333-4444-555555555555");
        assertThat(storage.storageKey(FESTIVAL_ID, MEDIA_ID)).isEqualTo(storage.storageKey(FESTIVAL_ID, MEDIA_ID));
        assertThat(storage.storageKey(FESTIVAL_ID, UUID.randomUUID()))
            .isNotEqualTo(storage.storageKey(FESTIVAL_ID, MEDIA_ID));
        assertThat(storage.stagingDirectory(operationId).toString())
            .startsWith(temporaryDirectory.toAbsolutePath().normalize().toString());
        assertThat(storage.finalDirectory(FESTIVAL_ID, MEDIA_ID).toString())
            .startsWith(temporaryDirectory.toAbsolutePath().normalize().toString());
    }

    @Test
    void publicApiAcceptsNoArbitraryStringOrPathArguments() {
        assertThat(Arrays.stream(MediaStorage.class.getDeclaredMethods())
            .map(Method::getParameterTypes)
            .flatMap(Arrays::stream))
            .doesNotContain(String.class, Path.class);
    }

    @Test
    void stagingAndFinalDirectoryUseTheSameFileStore() throws Exception {
        FileSystemMediaStorage storage = storage();
        UUID operationId = UUID.randomUUID();
        storage.createStaging(operationId);
        Path finalParent = storage.finalDirectory(FESTIVAL_ID, MEDIA_ID).getParent();
        Files.createDirectories(finalParent);

        assertThat(Files.getFileStore(storage.stagingDirectory(operationId)))
            .isEqualTo(Files.getFileStore(finalParent));
    }

    @Test
    void atomicallyFinalizesACompleteStagingDirectory() throws Exception {
        FileSystemMediaStorage storage = storage();
        UUID operationId = UUID.randomUUID();
        writeAllVariants(storage, operationId, "first");

        storage.finalizeStaging(operationId, FESTIVAL_ID, MEDIA_ID);

        assertThat(storage.stagingDirectory(operationId)).doesNotExist();
        assertThat(storage.finalDirectory(FESTIVAL_ID, MEDIA_ID)).isDirectory();
        for (MediaVariant variant : MediaVariant.values()) {
            try (var input = storage.open(FESTIVAL_ID, MEDIA_ID, variant)) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("first-" + variant.name());
            }
        }
    }

    @Test
    void refusesIncompleteStagingInsteadOfPublishingPartialMedia() throws Exception {
        FileSystemMediaStorage storage = storage();
        UUID operationId = UUID.randomUUID();
        storage.createStaging(operationId);
        try (var output = storage.openStagingOutput(operationId, MediaVariant.MASTER)) {
            output.write(new byte[]{1});
        }

        assertThatThrownBy(() -> storage.finalizeStaging(operationId, FESTIVAL_ID, MEDIA_ID))
            .isInstanceOf(IOException.class);
        assertThat(storage.stagingDirectory(operationId)).isDirectory();
        assertThat(storage.finalDirectory(FESTIVAL_ID, MEDIA_ID)).doesNotExist();
    }

    @Test
    void finalCollisionFailsWithoutReplacingExistingMedia() throws Exception {
        FileSystemMediaStorage storage = storage();
        UUID firstOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();
        writeAllVariants(storage, firstOperation, "original");
        storage.finalizeStaging(firstOperation, FESTIVAL_ID, MEDIA_ID);
        writeAllVariants(storage, secondOperation, "replacement");

        assertThatThrownBy(() -> storage.finalizeStaging(secondOperation, FESTIVAL_ID, MEDIA_ID))
            .isInstanceOf(FileAlreadyExistsException.class);
        try (var input = storage.open(FESTIVAL_ID, MEDIA_ID, MediaVariant.MASTER)) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("original-MASTER");
        }
        assertThat(storage.stagingDirectory(secondOperation)).isDirectory();
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        FileSystemMediaStorage storage = storage();
        UUID operationId = UUID.randomUUID();
        writeAllVariants(storage, operationId, "delete");
        storage.finalizeStaging(operationId, FESTIVAL_ID, MEDIA_ID);

        storage.delete(FESTIVAL_ID, MEDIA_ID);
        storage.delete(FESTIVAL_ID, MEDIA_ID);

        assertThat(storage.finalDirectory(FESTIVAL_ID, MEDIA_ID)).doesNotExist();
    }

    @Test
    void refusesSymlinkEscapeWhenThePlatformSupportsSymlinks() throws Exception {
        FileSystemMediaStorage storage = storage();
        Path outside = temporaryDirectory.resolveSibling("outside-" + UUID.randomUUID());
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("master.webp"), "outside");
        Path finalDirectory = storage.finalDirectory(FESTIVAL_ID, MEDIA_ID);
        Files.createDirectories(finalDirectory.getParent());
        try {
            Files.createSymbolicLink(finalDirectory, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic link creation unavailable: " + exception.getClass().getSimpleName());
        }

        assertThat(Files.isSymbolicLink(finalDirectory)).isTrue();
        assertThatThrownBy(() -> storage.open(FESTIVAL_ID, MEDIA_ID, MediaVariant.MASTER))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.delete(FESTIVAL_ID, MEDIA_ID))
            .isInstanceOf(IOException.class);
        assertThat(outside.resolve("master.webp")).exists();

        Files.delete(finalDirectory);
        Files.delete(outside.resolve("master.webp"));
        Files.delete(outside);
    }

    private FileSystemMediaStorage storage() throws IOException {
        return new FileSystemMediaStorage(temporaryDirectory);
    }

    private void writeAllVariants(FileSystemMediaStorage storage, UUID operationId, String prefix) throws Exception {
        storage.createStaging(operationId);
        for (MediaVariant variant : MediaVariant.values()) {
            try (var output = storage.openStagingOutput(operationId, variant)) {
                output.write((prefix + "-" + variant.name()).getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
