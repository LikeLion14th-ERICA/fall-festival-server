package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class StampReceiptVerifierTest {

    private static String hash(String code) {
        return HexFormat.of().formatHex(StampReceiptVerifier.sha256(code));
    }

    @Test
    void acceptsTheCurrentAndThePreviousCodeWhileRotating() {
        StampReceiptVerifier verifier = new StampReceiptVerifier(hash("048213") + ", " + hash("730915").toUpperCase());

        assertThat(verifier.configured()).isTrue();
        assertThat(verifier.matches("048213")).isTrue();
        assertThat(verifier.matches("730915")).isTrue();
        assertThat(verifier.matches("  730915 ")).isFalse();
        assertThat(verifier.matches("048214")).isFalse();
        assertThat(verifier.matches("")).isFalse();
        assertThat(verifier.matches(null)).isFalse();
    }

    @Test
    void acceptsOnlyExactlySixDigits() {
        StampReceiptVerifier verifier = new StampReceiptVerifier(hash("48213") + "," + hash("0482130") + "," + hash("O48213"));

        // Even a configured hash cannot make a code of another shape valid.
        assertThat(verifier.matches("48213")).isFalse();
        assertThat(verifier.matches("0482130")).isFalse();
        assertThat(verifier.matches("O48213")).isFalse();
        assertThat(verifier.matches(" 048213 ")).isFalse();
        assertThat(verifier.matches("048 213")).isFalse();
        assertThat(verifier.matches("٠٤٨٢١٣")).isFalse();
    }

    @Test
    void refusesEverythingWithoutAConfiguredHashAndRejectsAPlainCodeAsConfiguration() {
        assertThat(new StampReceiptVerifier("").configured()).isFalse();
        assertThat(new StampReceiptVerifier("").matches("048213")).isFalse();
        assertThatThrownBy(() -> new StampReceiptVerifier("048213"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("048213");
    }
}
