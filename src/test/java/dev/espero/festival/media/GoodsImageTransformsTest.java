package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoodsImageTransformsTest {

    private static final int A = 0xff100001;
    private static final int B = 0xff200002;
    private static final int C = 0xff300003;
    private static final int D = 0xff400004;
    private static final int E = 0xff500005;
    private static final int F = 0xff600006;

    @Test
    void mapsEveryExifOrientationToTheCorrectPixels() throws Exception {
        Map<Integer, int[][]> expected = Map.of(
            1, rows(row(A, B, C), row(D, E, F)),
            2, rows(row(C, B, A), row(F, E, D)),
            3, rows(row(F, E, D), row(C, B, A)),
            4, rows(row(D, E, F), row(A, B, C)),
            5, rows(row(A, D), row(B, E), row(C, F)),
            6, rows(row(D, A), row(E, B), row(F, C)),
            7, rows(row(F, C), row(E, B), row(D, A)),
            8, rows(row(C, F), row(B, E), row(A, D))
        );

        for (int orientation = 1; orientation <= 8; orientation++) {
            BufferedImage actual = GoodsImageTransforms.orient(source(), orientation);
            assertPixels(actual, expected.get(orientation));
        }
    }

    @Test
    void calculatesEveryRequiredOutputDimension() {
        assertThat(GoodsImageTransforms.masterDimension(1024)).isEqualTo(1024);
        assertThat(GoodsImageTransforms.masterDimension(2048)).isEqualTo(2048);
        assertThat(GoodsImageTransforms.masterDimension(2049)).isEqualTo(2048);
        assertThat(GoodsImageTransforms.masterDimension(4096)).isEqualTo(2048);
        assertThat(GoodsImageTransforms.THUMB_640_DIMENSION).isEqualTo(640);
        assertThat(GoodsImageTransforms.THUMB_320_DIMENSION).isEqualTo(320);
    }

    @Test
    void preservesAlphaDuringOrientationAndResize() throws Exception {
        BufferedImage source = GoodsImageTestFixtures.patternedImage(16, true);
        BufferedImage oriented = GoodsImageTransforms.orient(source, 6);
        BufferedImage resized = GoodsImageTransforms.resize(oriented, 8);

        assertThat(oriented.getColorModel().hasAlpha()).isTrue();
        assertThat(resized.getColorModel().hasAlpha()).isTrue();
        assertThat((resized.getRGB(2, 2) >>> 24) & 0xff).isLessThan(255);
    }

    private BufferedImage source() {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, A);
        image.setRGB(1, 0, B);
        image.setRGB(2, 0, C);
        image.setRGB(0, 1, D);
        image.setRGB(1, 1, E);
        image.setRGB(2, 1, F);
        return image;
    }

    private void assertPixels(BufferedImage actual, int[][] expected) {
        assertThat(actual.getHeight()).isEqualTo(expected.length);
        assertThat(actual.getWidth()).isEqualTo(expected[0].length);
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertThat(actual.getRGB(x, y))
                    .as("pixel (%s,%s)", x, y)
                    .isEqualTo(expected[y][x]);
            }
        }
    }

    private int[] row(int... pixels) {
        return pixels;
    }

    private int[][] rows(int[]... rows) {
        return rows;
    }
}
