package dev.espero.stamptest.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.stamptest.config.SecurityProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TokenSupportTest {

    private final TokenSupport tokens = new TokenSupport(new SecurityProperties(
        List.of("https://example.test"),
        "test-token-pepper-that-is-at-least-thirty-two-bytes",
        60,
        Duration.ofMinutes(1)
    ));

    @Test
    void acknowledgementTokenIsDeterministicScopedAndConstantTimeVerifiable() {
        UUID notificationId = UUID.randomUUID();
        String token = tokens.ackToken(notificationId);

        assertThat(token).hasSize(43).doesNotContain("=");
        assertThat(tokens.ackToken(notificationId)).isEqualTo(token);
        assertThat(tokens.validAckToken(notificationId, token)).isTrue();
        assertThat(tokens.validAckToken(UUID.randomUUID(), token)).isFalse();
        assertThat(tokens.validAckToken(notificationId, token + "x")).isFalse();
    }

    @Test
    void sessionTokenIsStoredAsAKeyedHash() {
        String raw = "raw-session-secret";
        String hash = tokens.sessionTokenHash(raw);

        assertThat(hash).hasSize(64).doesNotContain(raw);
        assertThat(tokens.hashMatches(raw, hash)).isTrue();
        assertThat(tokens.hashMatches("different", hash)).isFalse();
        assertThat(tokens.sha256(raw)).isNotEqualTo(hash);
    }
}
