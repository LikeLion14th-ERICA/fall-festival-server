package dev.espero.festival.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

final class GoodsImageTransforms {

    static final int MASTER_MAX_DIMENSION = 2048;
    static final int THUMB_640_DIMENSION = 640;
    static final int THUMB_320_DIMENSION = 320;

    private GoodsImageTransforms() {
    }

    static BufferedImage orient(BufferedImage source, int orientation) throws GoodsImageValidationException {
        GoodsImageInspector.validateOrientation(orientation);
        if (orientation == 1) {
            return source;
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int targetWidth = GoodsImageInspector.swapsAxes(orientation) ? sourceHeight : sourceWidth;
        int targetHeight = GoodsImageInspector.swapsAxes(orientation) ? sourceWidth : sourceHeight;
        BufferedImage target = image(targetWidth, targetHeight, source.getColorModel().hasAlpha());

        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                int targetX;
                int targetY;
                switch (orientation) {
                    case 2 -> {
                        targetX = sourceWidth - 1 - x;
                        targetY = y;
                    }
                    case 3 -> {
                        targetX = sourceWidth - 1 - x;
                        targetY = sourceHeight - 1 - y;
                    }
                    case 4 -> {
                        targetX = x;
                        targetY = sourceHeight - 1 - y;
                    }
                    case 5 -> {
                        targetX = y;
                        targetY = x;
                    }
                    case 6 -> {
                        targetX = sourceHeight - 1 - y;
                        targetY = x;
                    }
                    case 7 -> {
                        targetX = sourceHeight - 1 - y;
                        targetY = sourceWidth - 1 - x;
                    }
                    case 8 -> {
                        targetX = y;
                        targetY = sourceWidth - 1 - x;
                    }
                    default -> throw new IllegalStateException("Unexpected orientation " + orientation);
                }
                target.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return target;
    }

    static int masterDimension(int sourceDimension) {
        return Math.min(sourceDimension, MASTER_MAX_DIMENSION);
    }

    static BufferedImage resize(BufferedImage source, int targetDimension) {
        if (source.getWidth() == targetDimension && source.getHeight() == targetDimension) {
            return source;
        }
        BufferedImage current = source;
        while (current.getWidth() / 2 >= targetDimension) {
            int nextDimension = Math.max(targetDimension, current.getWidth() / 2);
            current = scale(current, nextDimension);
        }
        if (current.getWidth() != targetDimension || current.getHeight() != targetDimension) {
            current = scale(current, targetDimension);
        }
        return current;
    }

    private static BufferedImage scale(BufferedImage source, int dimension) {
        BufferedImage target = image(dimension, dimension, source.getColorModel().hasAlpha());
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.drawImage(source, 0, 0, dimension, dimension, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static BufferedImage image(int width, int height, boolean alpha) {
        return new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }
}
