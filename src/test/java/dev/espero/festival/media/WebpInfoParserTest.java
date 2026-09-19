package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebpInfoParserTest {

    @Test
    void parsesVp8xCanvasAndAnimationFlag() throws Exception {
        String output = """
            File: image.webp
            Canvas size 1024 x 1024
            Animation: 0
            No error detected.
            """;

        assertThat(WebpInfoParser.parse(output)).isEqualTo(new WebpInspection(1024, 1024, false));
    }

    @Test
    void parsesPlainVp8Dimensions() throws Exception {
        String output = """
            Chunk VP8 at offset 12, length 100
              Width: 1024
              Height: 1024
            No error detected.
            """;

        assertThat(WebpInfoParser.parse(output)).isEqualTo(new WebpInspection(1024, 1024, false));
    }

    @Test
    void detectsEveryAnimatedOutputSignal() throws Exception {
        assertThat(WebpInfoParser.parse(validCanvas("Animation: 1")).animated()).isTrue();
        assertThat(WebpInfoParser.parse(validCanvas("Chunk ANIM at offset 30")).animated()).isTrue();
        assertThat(WebpInfoParser.parse(validCanvas("Chunk ANMF at offset 42")).animated()).isTrue();
    }

    @Test
    void rejectsMissingOrInconsistentDimensionsAndCorruptOutput() {
        assertCorrupt("No error detected.");
        assertCorrupt("Width: 1024\nWidth: 2048\nHeight: 1024\nNo error detected.");
        assertCorrupt("Canvas size 1024 x 1024\nCanvas size 2048 x 2048\nNo error detected.");
        assertCorrupt("Canvas size 1024 x 1024");
    }

    private String validCanvas(String animationLine) {
        return "Canvas size 1024 x 1024\n" + animationLine + "\nNo error detected.\n";
    }

    private void assertCorrupt(String output) {
        assertThatThrownBy(() -> WebpInfoParser.parse(output))
            .isInstanceOfSatisfying(GoodsImageValidationException.class,
                exception -> assertThat(exception.reason()).isEqualTo(GoodsImageValidationReason.CORRUPT_IMAGE));
    }
}
