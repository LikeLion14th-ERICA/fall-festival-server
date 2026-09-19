package dev.espero.festival.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class GoodsImageInspector {

    static final long MAX_SOURCE_BYTES = 10L * 1024 * 1024;
    static final int MIN_DIMENSION = 1024;
    static final int MAX_DIMENSION = 4096;

    private static final byte[] PNG_SIGNATURE = new byte[]{
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final WebpTools webpTools;

    GoodsImageInspector(WebpTools webpTools) {
        this.webpTools = webpTools;
    }

    public GoodsImageInspection inspect(Path source) throws IOException {
        long sourceSize = Files.size(source);
        validateSourceSize(sourceSize);
        String sha256 = sha256(source);
        GoodsImageFormat format = detectFormat(readMagic(source));

        Dimensions dimensions;
        if (format == GoodsImageFormat.WEBP) {
            WebpInspection webp = webpTools.inspect(source);
            if (webp.animated()) {
                throw new GoodsImageValidationException(
                    GoodsImageValidationReason.ANIMATED_WEBP_NOT_SUPPORTED,
                    "Animated WebP images are not supported"
                );
            }
            dimensions = new Dimensions(webp.width(), webp.height());
        } else {
            dimensions = inspectWithImageReader(source, format);
        }

        int orientation = readOrientation(source);
        Dimensions oriented = validateDimensions(dimensions.width(), dimensions.height(), orientation);
        return new GoodsImageInspection(
            format,
            sha256,
            sourceSize,
            dimensions.width(),
            dimensions.height(),
            orientation,
            oriented.width(),
            oriented.height()
        );
    }

    static void validateSourceSize(long sourceSize) throws GoodsImageValidationException {
        if (sourceSize == 0) {
            throw new GoodsImageValidationException(GoodsImageValidationReason.EMPTY_FILE, "Image file is empty");
        }
        if (sourceSize < 0 || sourceSize > MAX_SOURCE_BYTES) {
            throw new GoodsImageValidationException(
                GoodsImageValidationReason.FILE_TOO_LARGE,
                "Image file exceeds the 10 MiB limit"
            );
        }
    }

    static GoodsImageFormat detectFormat(byte[] magic) throws GoodsImageValidationException {
        if (magic.length >= 3
            && unsigned(magic[0]) == 0xff
            && unsigned(magic[1]) == 0xd8
            && unsigned(magic[2]) == 0xff) {
            return GoodsImageFormat.JPEG;
        }
        if (startsWith(magic, PNG_SIGNATURE)) {
            return GoodsImageFormat.PNG;
        }
        if (magic.length >= 12
            && ascii(magic, 0, 4).equals("RIFF")
            && ascii(magic, 8, 4).equals("WEBP")) {
            return GoodsImageFormat.WEBP;
        }
        throw new GoodsImageValidationException(
            GoodsImageValidationReason.UNSUPPORTED_FORMAT,
            "Only JPEG, PNG, and WebP images are supported"
        );
    }

    static Dimensions validateDimensions(int width, int height, int orientation)
        throws GoodsImageValidationException {
        validateOrientation(orientation);
        int orientedWidth = swapsAxes(orientation) ? height : width;
        int orientedHeight = swapsAxes(orientation) ? width : height;
        if (orientedWidth != orientedHeight
            || orientedWidth < MIN_DIMENSION
            || orientedWidth > MAX_DIMENSION
            || orientedHeight < MIN_DIMENSION
            || orientedHeight > MAX_DIMENSION) {
            throw new GoodsImageValidationException(
                GoodsImageValidationReason.INVALID_DIMENSIONS,
                "Oriented image dimensions must be square and between 1024 and 4096 pixels"
            );
        }
        return new Dimensions(orientedWidth, orientedHeight);
    }

    static void validateOrientation(int orientation) throws GoodsImageValidationException {
        if (orientation < 1 || orientation > 8) {
            throw new GoodsImageValidationException(
                GoodsImageValidationReason.INVALID_EXIF_ORIENTATION,
                "EXIF orientation must be between 1 and 8"
            );
        }
    }

    static boolean swapsAxes(int orientation) {
        return orientation >= 5;
    }

    private byte[] readMagic(Path source) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            return input.readNBytes(12);
        }
    }

    private String sha256(Path source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (var input = new DigestInputStream(new BufferedInputStream(Files.newInputStream(source)), digest)) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) != -1) {
                // DigestInputStream updates the digest while streaming.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Dimensions inspectWithImageReader(Path source, GoodsImageFormat expectedFormat) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            if (input == null) {
                throw corrupt("Image header cannot be read", null);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw corrupt("No ImageIO reader accepts the image header", null);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!matches(expectedFormat, format)) {
                    throw corrupt("Image header does not match its magic bytes", null);
                }
                return new Dimensions(reader.getWidth(0), reader.getHeight(0));
            } catch (GoodsImageValidationException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                throw corrupt("Image dimensions cannot be read", exception);
            } finally {
                reader.dispose();
            }
        }
    }

    private int readOrientation(Path source) throws IOException {
        try {
            var metadata = ImageMetadataReader.readMetadata(source.toFile());
            var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return 1;
            }
            Integer orientation = directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            if (orientation == null) {
                throw new GoodsImageValidationException(
                    GoodsImageValidationReason.INVALID_EXIF_ORIENTATION,
                    "EXIF orientation is malformed"
                );
            }
            validateOrientation(orientation);
            return orientation;
        } catch (GoodsImageValidationException exception) {
            throw exception;
        } catch (ImageProcessingException | RuntimeException exception) {
            throw corrupt("Image metadata cannot be read", exception);
        }
    }

    private static boolean matches(GoodsImageFormat expected, String actual) {
        return switch (expected) {
            case JPEG -> actual.equalsIgnoreCase("JPEG") || actual.equalsIgnoreCase("JPG");
            case PNG -> actual.equalsIgnoreCase("PNG");
            case WEBP -> false;
        };
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static String ascii(byte[] value, int offset, int length) {
        return new String(value, offset, length, StandardCharsets.US_ASCII);
    }

    private static GoodsImageValidationException corrupt(String message, Throwable cause) {
        return new GoodsImageValidationException(GoodsImageValidationReason.CORRUPT_IMAGE, message, cause);
    }

    record Dimensions(int width, int height) {
    }
}
