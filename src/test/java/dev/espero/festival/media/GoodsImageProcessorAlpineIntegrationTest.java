package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "media.codec.external-tools.required", matches = "true")
class GoodsImageProcessorAlpineIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    private ExternalWebpTools webpTools;
    private ExternalProcessRunner processRunner;
    private GoodsImageInspector inspector;
    private GoodsImageProcessor processor;
    private FileSystemMediaStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        processRunner = new ExternalProcessRunner();
        webpTools = new ExternalWebpTools(processRunner);
        inspector = new GoodsImageInspector(webpTools);
        storage = new FileSystemMediaStorage(temporaryDirectory.resolve("media"));
        processor = new GoodsImageProcessor(storage, webpTools);
    }

    @Test
    void processesPngToAllWebpVariantsAndPreservesAlpha() throws Exception {
        Path source = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("source.png"), "png", 1024, true
        );

        FinalizedMedia media = processAndFinalize(source);

        assertVariants(media, 1024);
        Path master = copyVariant(media, MediaVariant.MASTER);
        Path decoded = temporaryDirectory.resolve("alpha-decoded.png");
        webpTools.decode(master, decoded);
        BufferedImage image = ImageIO.read(decoded.toFile());
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat((image.getRGB(128, 128) >>> 24) & 0xff).isLessThan(255);
    }

    @Test
    void processesJpegToAllWebpVariants() throws Exception {
        Path source = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("source.jpg"), "jpeg", 1024, false
        );

        FinalizedMedia media = processAndFinalize(source);

        assertThat(media.result().sourceFormat()).isEqualTo(GoodsImageFormat.JPEG);
        assertVariants(media, 1024);
    }

    @Test
    void processesWebpInputAfterHeaderInspection() throws Exception {
        Path png = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("webp-source.png"), "png", 1024, true
        );
        Path source = temporaryDirectory.resolve("source.webp");
        webpTools.encode(png, source);

        FinalizedMedia media = processAndFinalize(source);

        assertThat(media.result().sourceFormat()).isEqualTo(GoodsImageFormat.WEBP);
        assertVariants(media, 1024);
    }

    @Test
    void downscalesMasterLargerThan2048() throws Exception {
        Path source = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("large.png"), "png", 2049, false
        );

        FinalizedMedia media = processAndFinalize(source);

        assertThat(media.result().masterWidth()).isEqualTo(2048);
        assertVariants(media, 2048);
    }

    @Test
    void rejectsAnimatedWebpDuringInspectionBeforeDecode() throws Exception {
        Path first = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("frame-1.png"), "png", 64, false
        );
        Path second = GoodsImageTestFixtures.writeImage(
            temporaryDirectory.resolve("frame-2.png"), "png", 64, true
        );
        Path animated = temporaryDirectory.resolve("animated.webp");
        var result = processRunner.run(List.of(
            ExternalWebpTools.IMG2WEBP.toString(),
            "-loop", "0",
            "-d", "100", first.toString(),
            "-d", "100", second.toString(),
            "-o", animated.toString()
        ));
        assertThat(result.exitCode()).withFailMessage(result.output()).isZero();

        assertThatThrownBy(() -> inspector.inspect(animated))
            .isInstanceOfSatisfying(GoodsImageValidationException.class,
                exception -> assertThat(exception.reason())
                    .isEqualTo(GoodsImageValidationReason.ANIMATED_WEBP_NOT_SUPPORTED));
    }

    private FinalizedMedia processAndFinalize(Path source) throws Exception {
        GoodsImageInspection inspection = inspector.inspect(source);
        UUID operationId = UUID.randomUUID();
        UUID festivalId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        ProcessedGoodsImage result = processor.process(source, inspection, operationId);
        assertThat(storage.stagingDirectory(operationId)).isDirectory();
        storage.finalizeStaging(operationId, festivalId, mediaId);
        return new FinalizedMedia(result, festivalId, mediaId);
    }

    private void assertVariants(FinalizedMedia media, int masterDimension) throws Exception {
        assertVariant(media, MediaVariant.MASTER, masterDimension);
        assertVariant(media, MediaVariant.THUMB_640, 640);
        assertVariant(media, MediaVariant.THUMB_320, 320);
    }

    private void assertVariant(FinalizedMedia media, MediaVariant variant, int dimension) throws Exception {
        Path output = copyVariant(media, variant);
        byte[] header = Files.readAllBytes(output);
        assertThat(new String(header, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(header, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
        assertThat(webpTools.inspect(output)).isEqualTo(new WebpInspection(dimension, dimension, false));

        var details = processRunner.run(List.of(ExternalWebpTools.WEBPINFO.toString(), "-diag", output.toString()));
        assertThat(details.exitCode()).isZero();
        assertThat(details.output()).doesNotContain("Chunk EXIF", "Chunk XMP");
    }

    private Path copyVariant(FinalizedMedia media, MediaVariant variant) throws IOException {
        Path output = temporaryDirectory.resolve(media.mediaId() + "-" + variant.filename());
        try (InputStream input = storage.open(media.festivalId(), media.mediaId(), variant)) {
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
    }

    private record FinalizedMedia(ProcessedGoodsImage result, UUID festivalId, UUID mediaId) {
    }
}
