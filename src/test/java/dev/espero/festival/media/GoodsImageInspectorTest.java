package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoodsImageInspectorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsSupportedFormatsFromMagicBytesOnly() throws Exception {
        assertThat(GoodsImageInspector.detectFormat(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
            .isEqualTo(GoodsImageFormat.JPEG);
        assertThat(GoodsImageInspector.detectFormat(new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        })).isEqualTo(GoodsImageFormat.PNG);
        assertThat(GoodsImageInspector.detectFormat("RIFF0000WEBP".getBytes()))
            .isEqualTo(GoodsImageFormat.WEBP);
    }

    @Test
    void rejectsUnsupportedMagicBytes() {
        assertThatThrownBy(() -> GoodsImageInspector.detectFormat("GIF89a".getBytes()))
            .isInstanceOfSatisfying(GoodsImageValidationException.class,
                exception -> assertThat(exception.reason()).isEqualTo(GoodsImageValidationReason.UNSUPPORTED_FORMAT));
    }

    @Test
    void enforcesExactSourceSizeBoundaries() throws Exception {
        assertReason(() -> GoodsImageInspector.validateSourceSize(0), GoodsImageValidationReason.EMPTY_FILE);
        GoodsImageInspector.validateSourceSize(GoodsImageInspector.MAX_SOURCE_BYTES);
        assertReason(
            () -> GoodsImageInspector.validateSourceSize(GoodsImageInspector.MAX_SOURCE_BYTES + 1),
            GoodsImageValidationReason.FILE_TOO_LARGE
        );
    }

    @Test
    void validatesOrientedSquareDimensionBoundaries() throws Exception {
        assertThat(GoodsImageInspector.validateDimensions(1024, 1024, 1))
            .isEqualTo(new GoodsImageInspector.Dimensions(1024, 1024));
        assertThat(GoodsImageInspector.validateDimensions(4096, 4096, 1))
            .isEqualTo(new GoodsImageInspector.Dimensions(4096, 4096));
        assertReason(() -> GoodsImageInspector.validateDimensions(1023, 1023, 1),
            GoodsImageValidationReason.INVALID_DIMENSIONS);
        assertReason(() -> GoodsImageInspector.validateDimensions(4097, 4097, 1),
            GoodsImageValidationReason.INVALID_DIMENSIONS);
        assertReason(() -> GoodsImageInspector.validateDimensions(1024, 1200, 1),
            GoodsImageValidationReason.INVALID_DIMENSIONS);
        assertThat(GoodsImageInspector.swapsAxes(6)).isTrue();
        assertThat(GoodsImageInspector.swapsAxes(8)).isTrue();
    }

    @Test
    void streamsSha256AndReadsPngHeaderWithoutRasterDecode() throws Exception {
        Path source = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("source.bin"), "png", 1024, true
        );
        GoodsImageInspection inspection = inspector().inspect(source);

        assertThat(inspection.format()).isEqualTo(GoodsImageFormat.PNG);
        assertThat(inspection.sourceWidth()).isEqualTo(1024);
        assertThat(inspection.sourceHeight()).isEqualTo(1024);
        assertThat(inspection.orientation()).isEqualTo(1);
        assertThat(inspection.sourceSha256()).matches("[0-9a-f]{64}");
        assertThat(HexFormat.of().parseHex(inspection.sourceSha256())).hasSize(32);
    }

    @Test
    void acceptsEveryExifOrientationAndDefaultsMissingOrientationToOne() throws Exception {
        for (int orientation : IntStream.rangeClosed(1, 8).toArray()) {
            Path source = temporaryDirectory.resolve("orientation-" + orientation + ".jpg");
            Files.write(source, GoodsImageTestFixtures.jpegWithOrientation(1024, orientation));
            assertThat(inspector().inspect(source).orientation()).isEqualTo(orientation);
        }

        Path withoutExif = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("without-exif.jpg"), "jpeg", 1024, false
        );
        assertThat(inspector().inspect(withoutExif).orientation()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidExifOrientation() throws Exception {
        Path source = temporaryDirectory.resolve("invalid-orientation.jpg");
        Files.write(source, GoodsImageTestFixtures.jpegWithOrientation(1024, 9));

        assertReason(() -> inspector().inspect(source), GoodsImageValidationReason.INVALID_EXIF_ORIENTATION);
    }

    private GoodsImageInspector inspector() {
        return new GoodsImageInspector(new UnexpectedWebpTools());
    }

    private void assertReason(ThrowingCall call, GoodsImageValidationReason expected) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(GoodsImageValidationException.class,
                exception -> assertThat(exception.reason()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class UnexpectedWebpTools implements WebpTools {
        @Override
        public WebpInspection inspect(Path source) {
            throw new AssertionError("WebP tool must not be called for JPEG/PNG inspection");
        }

        @Override
        public void decode(Path source, Path outputPng) {
            throw new AssertionError("Unexpected decode");
        }

        @Override
        public void encode(Path inputPng, Path outputWebp) {
            throw new AssertionError("Unexpected encode");
        }
    }
}
