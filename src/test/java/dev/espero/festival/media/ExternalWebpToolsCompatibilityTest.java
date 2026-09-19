package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class ExternalWebpToolsCompatibilityTest {

    private static final int IMAGE_SIZE = 1024;

    private final ExternalWebpTools webpTools = new ExternalWebpTools();

    @TempDir
    Path temporaryDirectory;

    @Nested
    @EnabledIfSystemProperty(named = "media.codec.external-tools.required", matches = "true")
    class ExternalTools {

        @Test
        void repeatedlyEncodesPngAndDecodesWebp() throws Exception {
            assertThat(ExternalWebpTools.CWEBP).isExecutable();
            assertThat(ExternalWebpTools.DWEBP).isExecutable();
            Path input = writeImage("input.png", "png");

            for (int iteration = 0; iteration < 10; iteration++) {
                Path webp = temporaryDirectory.resolve("output-" + iteration + ".webp");
                Path decoded = temporaryDirectory.resolve("decoded-" + iteration + ".png");

                encode(input, webp);
                assertWebpContainer(webp);
                decode(webp, decoded);
                assertDecodedImage(decoded);
            }
        }

        @Test
        void encodesJpegAndDecodesWebp() throws Exception {
            Path input = writeImage("input.jpg", "jpeg");
            Path webp = temporaryDirectory.resolve("jpeg-input.webp");
            Path decoded = temporaryDirectory.resolve("jpeg-decoded.png");

            encode(input, webp);
            assertWebpContainer(webp);
            decode(webp, decoded);
            assertDecodedImage(decoded);
        }
    }

    @Test
    void readsSyntheticExifOrientation() throws Exception {
        byte[] jpeg = imageBytes("jpeg");
        byte[] orientedJpeg = GoodsImageTestFixtures.insertExifOrientation(jpeg, 6);

        var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(orientedJpeg));
        var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);

        assertThat(directory).isNotNull();
        assertThat(directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(6);
    }

    @Test
    void handlesJpegWithoutExifOrientation() throws Exception {
        var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(imageBytes("jpeg")));
        var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);

        assertThat(directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)).isTrue();
    }

    private Path writeImage(String fileName, String format) throws IOException {
        Path path = temporaryDirectory.resolve(fileName);
        assertThat(ImageIO.write(createImage(), format, path.toFile())).isTrue();
        return path;
    }

    private byte[] imageBytes(String format) throws IOException {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(createImage(), format, output)).isTrue();
        return output.toByteArray();
    }

    private BufferedImage createImage() {
        var image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            int half = IMAGE_SIZE / 2;
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, half, half);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(half, 0, half, half);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, half, half, half);
            graphics.setColor(Color.YELLOW);
            graphics.fillRect(half, half, half, half);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void encode(Path input, Path output) throws Exception {
        webpTools.encode(input, output);
    }

    private void decode(Path input, Path output) throws Exception {
        webpTools.decode(input, output);
    }

    private void assertWebpContainer(Path webp) throws IOException {
        assertThat(webp).isRegularFile();
        assertThat(Files.size(webp)).isGreaterThan(12L);
        byte[] header = Files.readAllBytes(webp);
        assertThat(new String(header, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(header, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
    }

    private void assertDecodedImage(Path decoded) throws IOException {
        assertThat(decoded).isRegularFile();
        BufferedImage image = ImageIO.read(decoded.toFile());
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(IMAGE_SIZE);
        assertThat(image.getHeight()).isEqualTo(IMAGE_SIZE);
        assertDominant(image.getRGB(IMAGE_SIZE / 4, IMAGE_SIZE / 4), Color.RED);
        assertDominant(image.getRGB(IMAGE_SIZE * 3 / 4, IMAGE_SIZE / 4), Color.GREEN);
        assertDominant(image.getRGB(IMAGE_SIZE / 4, IMAGE_SIZE * 3 / 4), Color.BLUE);
        Color yellow = new Color(image.getRGB(IMAGE_SIZE * 3 / 4, IMAGE_SIZE * 3 / 4));
        assertThat(yellow.getRed()).isGreaterThan(yellow.getBlue() + 40);
        assertThat(yellow.getGreen()).isGreaterThan(yellow.getBlue() + 40);
    }

    private void assertDominant(int rgb, Color expected) {
        Color actual = new Color(rgb);
        if (expected == Color.RED) {
            assertThat(actual.getRed()).isGreaterThan(actual.getGreen() + 40);
            assertThat(actual.getRed()).isGreaterThan(actual.getBlue() + 40);
        } else if (expected == Color.GREEN) {
            assertThat(actual.getGreen()).isGreaterThan(actual.getRed() + 40);
            assertThat(actual.getGreen()).isGreaterThan(actual.getBlue() + 40);
        } else {
            assertThat(actual.getBlue()).isGreaterThan(actual.getRed() + 40);
            assertThat(actual.getBlue()).isGreaterThan(actual.getGreen() + 40);
        }
    }

}
