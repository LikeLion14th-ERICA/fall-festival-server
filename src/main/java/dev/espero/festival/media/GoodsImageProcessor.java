package dev.espero.festival.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;

public final class GoodsImageProcessor {

    private static final Semaphore GLOBAL_PROCESSING_PERMITS = new Semaphore(2);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    private final MediaStorage mediaStorage;
    private final WebpTools webpTools;
    private final Semaphore processingPermits;
    private final Path temporaryRoot;

    GoodsImageProcessor(MediaStorage mediaStorage, WebpTools webpTools) {
        this(mediaStorage, webpTools, GLOBAL_PROCESSING_PERMITS, null);
    }

    GoodsImageProcessor(
        MediaStorage mediaStorage,
        WebpTools webpTools,
        Semaphore processingPermits,
        Path temporaryRoot
    ) {
        this.mediaStorage = mediaStorage;
        this.webpTools = webpTools;
        this.processingPermits = processingPermits;
        this.temporaryRoot = temporaryRoot;
    }

    public ProcessedGoodsImage process(Path source, GoodsImageInspection inspection, UUID operationId)
        throws IOException, MediaProcessingBusyException {
        if (!processingPermits.tryAcquire()) {
            throw new MediaProcessingBusyException(RETRY_AFTER);
        }

        Path workingDirectory = null;
        boolean stagingCreated = false;
        try {
            workingDirectory = createWorkingDirectory();
            mediaStorage.createStaging(operationId);
            stagingCreated = true;

            BufferedImage decoded = decode(source, inspection.format(), workingDirectory);
            requireDimensions(decoded, inspection.sourceWidth(), inspection.sourceHeight(), "Decoded image");
            BufferedImage oriented = GoodsImageTransforms.orient(decoded, inspection.orientation());
            requireDimensions(oriented, inspection.orientedWidth(), inspection.orientedHeight(), "Oriented image");

            int masterDimension = GoodsImageTransforms.masterDimension(oriented.getWidth());
            encodeVariant(
                GoodsImageTransforms.resize(oriented, masterDimension),
                MediaVariant.MASTER,
                operationId,
                workingDirectory
            );
            encodeVariant(
                GoodsImageTransforms.resize(oriented, GoodsImageTransforms.THUMB_640_DIMENSION),
                MediaVariant.THUMB_640,
                operationId,
                workingDirectory
            );
            encodeVariant(
                GoodsImageTransforms.resize(oriented, GoodsImageTransforms.THUMB_320_DIMENSION),
                MediaVariant.THUMB_320,
                operationId,
                workingDirectory
            );

            deleteTree(workingDirectory);
            workingDirectory = null;
            return new ProcessedGoodsImage(
                inspection.sourceSha256(),
                inspection.sourceSizeBytes(),
                inspection.format(),
                inspection.sourceWidth(),
                inspection.sourceHeight(),
                masterDimension,
                masterDimension
            );
        } catch (IOException | RuntimeException exception) {
            if (stagingCreated) {
                suppressCleanup(exception, () -> mediaStorage.discardStaging(operationId));
            }
            if (workingDirectory != null) {
                Path directory = workingDirectory;
                suppressCleanup(exception, () -> deleteTree(directory));
            }
            throw exception;
        } finally {
            processingPermits.release();
        }
    }

    private BufferedImage decode(Path source, GoodsImageFormat format, Path workingDirectory) throws IOException {
        BufferedImage decoded;
        if (format == GoodsImageFormat.WEBP) {
            Path decodedPng = workingDirectory.resolve("decoded.png");
            webpTools.decode(source, decodedPng);
            decoded = ImageIO.read(decodedPng.toFile());
        } else {
            decoded = ImageIO.read(source.toFile());
        }
        if (decoded == null) {
            throw new GoodsImageValidationException(
                GoodsImageValidationReason.CORRUPT_IMAGE,
                "Validated image could not be decoded"
            );
        }
        return decoded;
    }

    private void encodeVariant(
        BufferedImage image,
        MediaVariant variant,
        UUID operationId,
        Path workingDirectory
    ) throws IOException {
        String baseName = variant.name().toLowerCase();
        Path intermediatePng = workingDirectory.resolve(baseName + ".png");
        Path encodedWebp = workingDirectory.resolve(baseName + ".webp");
        if (!ImageIO.write(image, "png", intermediatePng.toFile())) {
            throw new IOException("PNG intermediate encoder is unavailable");
        }
        webpTools.encode(intermediatePng, encodedWebp);
        try (var output = mediaStorage.openStagingOutput(operationId, variant)) {
            Files.copy(encodedWebp, output);
        }
        Files.delete(intermediatePng);
        Files.delete(encodedWebp);
    }

    private Path createWorkingDirectory() throws IOException {
        if (temporaryRoot == null) {
            return Files.createTempDirectory("festival-goods-image-");
        }
        Files.createDirectories(temporaryRoot);
        return Files.createTempDirectory(temporaryRoot, "festival-goods-image-");
    }

    private void requireDimensions(BufferedImage image, int expectedWidth, int expectedHeight, String stage)
        throws GoodsImageValidationException {
        if (image.getWidth() != expectedWidth || image.getHeight() != expectedHeight) {
            throw new GoodsImageValidationException(
                GoodsImageValidationReason.CORRUPT_IMAGE,
                stage + " dimensions differ from inspected dimensions"
            );
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void suppressCleanup(Exception original, Cleanup cleanup) {
        try {
            cleanup.run();
        } catch (IOException | RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws IOException;
    }
}
