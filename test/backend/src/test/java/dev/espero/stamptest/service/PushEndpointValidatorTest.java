package dev.espero.stamptest.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.stamptest.config.PushProperties;
import dev.espero.stamptest.web.ApiException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class PushEndpointValidatorTest {

    private final PushEndpointValidator validator = new PushEndpointValidator(new PushProperties(
        true,
        "public",
        "private",
        "mailto:test@example.com",
        List.of(
            "fcm.googleapis.com", "jmt17.google.com", ".push.apple.com",
            ".push.services.mozilla.com", ".notify.windows.com"
        ),
        Duration.ofSeconds(70),
        3,
        Duration.ofSeconds(5),
        Duration.ofSeconds(15)
    ));

    @Test
    void acceptsKnownChromiumWebkitAndMozillaPushHosts() {
        for (String endpoint : List.of(
            "https://fcm.googleapis.com/fcm/send/id",
            "https://jmt17.google.com/fcm/send/id",
            "https://web.push.apple.com/QH5abc",
            "https://updates.push.services.mozilla.com/wpush/v2/id",
            "https://wns2-db5p.notify.windows.com/w/id"
        )) {
            assertThatCode(() -> validator.validate(endpoint, publicKey(), auth())).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsLookalikeHostsInsecureUrlsPortsAndMalformedKeys() {
        assertInvalid("https://web.push.apple.com.evil.test/id", publicKey(), auth());
        assertInvalid("http://fcm.googleapis.com/id", publicKey(), auth());
        assertInvalid("https://fcm.googleapis.com:8443/id", publicKey(), auth());
        assertInvalid("https://fcm.googleapis.com/id", "bad", auth());
        assertInvalid("https://fcm.googleapis.com/id", publicKey(), "bad");
    }

    private void assertInvalid(String endpoint, String p256dh, String auth) {
        assertThatThrownBy(() -> validator.validate(endpoint, p256dh, auth))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).code())
            .isEqualTo("INVALID_PUSH_SUBSCRIPTION");
    }

    private String publicKey() {
        byte[] key = new byte[65];
        key[0] = 0x04;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private String auth() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    }
}
