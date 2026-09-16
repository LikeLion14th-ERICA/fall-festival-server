package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderTest {

    @Test
    void bcryptMatchesOnlyTheOriginalPlaintext() {
        PasswordEncoder encoder = new SecurityConfiguration().passwordEncoder();

        String hash = encoder.encode("correct-password");

        assertThat(hash).startsWith("$2");
        assertThat(hash).doesNotContain("correct-password");
        assertThat(encoder.matches("correct-password", hash)).isTrue();
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }
}
