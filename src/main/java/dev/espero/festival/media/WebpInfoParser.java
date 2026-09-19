package dev.espero.festival.media;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebpInfoParser {

    private static final Pattern CANVAS = Pattern.compile("(?m)^\\s*Canvas size\\s+(\\d+)\\s*x\\s*(\\d+)\\s*$");
    private static final Pattern WIDTH = Pattern.compile("(?m)^\\s*Width:\\s*(\\d+)\\s*$");
    private static final Pattern HEIGHT = Pattern.compile("(?m)^\\s*Height:\\s*(\\d+)\\s*$");
    private static final Pattern ANIMATION = Pattern.compile("(?m)^\\s*Animation:\\s*1\\s*$");
    private static final Pattern ANIMATION_CHUNK = Pattern.compile("(?m)^\\s*Chunk\\s+(ANIM|ANMF)\\b");

    private WebpInfoParser() {
    }

    static WebpInspection parse(String output) throws GoodsImageValidationException {
        if (output == null || !output.contains("No error detected")) {
            throw corrupt("webpinfo did not confirm a valid WebP container");
        }

        boolean animated = ANIMATION.matcher(output).find() || ANIMATION_CHUNK.matcher(output).find();
        Set<Dimensions> canvases = dimensions(CANVAS, output);
        if (canvases.size() > 1) {
            throw corrupt("webpinfo reported inconsistent canvas dimensions");
        }
        if (!canvases.isEmpty()) {
            Dimensions canvas = canvases.iterator().next();
            requirePositive(canvas.width(), canvas.height());
            return new WebpInspection(canvas.width(), canvas.height(), animated);
        }

        Set<Integer> widths = values(WIDTH, output);
        Set<Integer> heights = values(HEIGHT, output);
        if (widths.size() != 1 || heights.size() != 1) {
            throw corrupt("webpinfo did not report one consistent image dimension");
        }
        int width = widths.iterator().next();
        int height = heights.iterator().next();
        requirePositive(width, height);
        return new WebpInspection(width, height, animated);
    }

    private static Set<Dimensions> dimensions(Pattern pattern, String output) {
        Set<Dimensions> dimensions = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(output);
        while (matcher.find()) {
            dimensions.add(new Dimensions(parsePositive(matcher.group(1)), parsePositive(matcher.group(2))));
        }
        return dimensions;
    }

    private static Set<Integer> values(Pattern pattern, String output) {
        Set<Integer> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(output);
        while (matcher.find()) {
            values.add(parsePositive(matcher.group(1)));
        }
        return values;
    }

    private static int parsePositive(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException("Dimension must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static void requirePositive(int width, int height) throws GoodsImageValidationException {
        if (width <= 0 || height <= 0) {
            throw corrupt("webpinfo reported a non-positive image dimension");
        }
    }

    private static GoodsImageValidationException corrupt(String message) {
        return new GoodsImageValidationException(GoodsImageValidationReason.CORRUPT_IMAGE, message);
    }

    private record Dimensions(int width, int height) {
    }
}
