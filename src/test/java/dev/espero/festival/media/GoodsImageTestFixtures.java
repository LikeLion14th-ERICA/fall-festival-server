package dev.espero.festival.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.imageio.ImageIO;

final class GoodsImageTestFixtures {

    private GoodsImageTestFixtures() {
    }

    static BufferedImage patternedImage(int size, boolean alpha) {
        BufferedImage image = new BufferedImage(
            size,
            size,
            alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            int half = size / 2;
            graphics.setColor(new Color(255, 0, 0, alpha ? 128 : 255));
            graphics.fillRect(0, 0, half, half);
            graphics.setColor(new Color(0, 255, 0, alpha ? 160 : 255));
            graphics.fillRect(half, 0, size - half, half);
            graphics.setColor(new Color(0, 0, 255, alpha ? 192 : 255));
            graphics.fillRect(0, half, half, size - half);
            graphics.setColor(new Color(255, 255, 0, alpha ? 224 : 255));
            graphics.fillRect(half, half, size - half, size - half);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    static Path writeImage(Path path, String format, int size, boolean alpha) throws IOException {
        if (!ImageIO.write(patternedImage(size, alpha), format, path.toFile())) {
            throw new IOException("ImageIO writer unavailable for " + format);
        }
        return path;
    }

    static byte[] jpegWithOrientation(int size, int orientation) throws IOException {
        var jpeg = new ByteArrayOutputStream();
        if (!ImageIO.write(patternedImage(size, false), "jpeg", jpeg)) {
            throw new IOException("JPEG writer unavailable");
        }
        return insertExifOrientation(jpeg.toByteArray(), orientation);
    }

    static byte[] insertExifOrientation(byte[] jpeg, int orientation) throws IOException {
        ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I');
        tiff.putShort((short) 42);
        tiff.putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112);
        tiff.putShort((short) 3);
        tiff.putInt(1);
        tiff.putShort((short) orientation);
        tiff.putShort((short) 0);
        tiff.putInt(0);

        var payload = new ByteArrayOutputStream();
        payload.write("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        payload.write(tiff.array());

        var result = new ByteArrayOutputStream(jpeg.length + payload.size() + 4);
        result.write(jpeg, 0, 2);
        try (var data = new DataOutputStream(result)) {
            data.writeByte(0xff);
            data.writeByte(0xe1);
            data.writeShort(payload.size() + 2);
            payload.writeTo(data);
            data.write(jpeg, 2, jpeg.length - 2);
        }
        return result.toByteArray();
    }
}
