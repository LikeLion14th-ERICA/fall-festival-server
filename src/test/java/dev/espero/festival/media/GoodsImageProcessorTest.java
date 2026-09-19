package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoodsImageProcessorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsThirdConcurrentProcessingImmediatelyAndRecoversPermits() throws Exception {
        Path source = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("source.png"), "png", 1024, true
        );
        FileSystemMediaStorage storage = new FileSystemMediaStorage(temporaryDirectory.resolve("media"));
        var permits = new Semaphore(2);
        var tools = new BlockingWebpTools();
        var processor = new GoodsImageProcessor(
            storage,
            tools,
            permits,
            temporaryDirectory.resolve("work")
        );
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> processor.process(source, inspection(source), first));
            var secondResult = executor.submit(() -> processor.process(source, inspection(source), second));
            assertThat(tools.awaitBothEncoders()).isTrue();

            assertThatThrownBy(() -> processor.process(source, inspection(source), third))
                .isInstanceOf(MediaProcessingBusyException.class);
            assertThat(storage.stagingDirectory(third)).doesNotExist();

            tools.release();
            assertThat(firstResult.get(30, TimeUnit.SECONDS)).isNotNull();
            assertThat(secondResult.get(30, TimeUnit.SECONDS)).isNotNull();
        }

        assertThat(permits.availablePermits()).isEqualTo(2);
        assertThat(processor.process(source, inspection(source), third)).isNotNull();
        assertThat(permits.availablePermits()).isEqualTo(2);
    }

    @Test
    void encodeFailureRemovesPartialStagingAndTemporaryFilesAndReturnsPermit() throws Exception {
        Path source = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("failure-source.png"), "png", 1024, false
        );
        Path workRoot = temporaryDirectory.resolve("failure-work");
        FileSystemMediaStorage storage = new FileSystemMediaStorage(temporaryDirectory.resolve("failure-media"));
        var permits = new Semaphore(1);
        var processor = new GoodsImageProcessor(storage, new FailingWebpTools(), permits, workRoot);
        UUID operationId = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process(source, inspection(source), operationId))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("intentional encode failure");

        assertThat(storage.stagingDirectory(operationId)).doesNotExist();
        try (var entries = Files.list(workRoot)) {
            assertThat(entries).isEmpty();
        }
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    private GoodsImageInspection inspection(Path source) throws IOException {
        return new GoodsImageInspection(
            GoodsImageFormat.PNG,
            "0".repeat(64),
            Files.size(source),
            1024,
            1024,
            1,
            1024,
            1024
        );
    }

    private static class WritingWebpTools implements WebpTools {
        @Override
        public WebpInspection inspect(Path source) {
            return new WebpInspection(1024, 1024, false);
        }

        @Override
        public void decode(Path source, Path outputPng) {
            throw new AssertionError("PNG processing must not use dwebp");
        }

        @Override
        public void encode(Path inputPng, Path outputWebp) throws IOException {
            Files.writeString(outputWebp, "fake-webp");
        }
    }

    private static final class BlockingWebpTools extends WritingWebpTools {
        private final AtomicInteger firstEncodes = new AtomicInteger();
        private final CountDownLatch bothEncoders = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void encode(Path inputPng, Path outputWebp) throws IOException {
            if (firstEncodes.getAndIncrement() < 2) {
                bothEncoders.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted test encoder", exception);
                }
            }
            super.encode(inputPng, outputWebp);
        }

        boolean awaitBothEncoders() throws InterruptedException {
            return bothEncoders.await(10, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }

    private static final class FailingWebpTools extends WritingWebpTools {
        @Override
        public void encode(Path inputPng, Path outputWebp) throws IOException {
            throw new IOException("intentional encode failure");
        }
    }
}
