package dev.espero.stamptest.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StandardWebPushGatewayTest {

    private static final Instant NOW = Instant.parse("2015-10-21T07:27:50Z");

    @Test
    void parsesDeltaSecondsAndHttpDateRetryAfterForms() {
        assertThat(StandardWebPushGateway.parseRetryAfter("15", NOW)).isEqualTo(Duration.ofSeconds(15));
        assertThat(StandardWebPushGateway.parseRetryAfter(
            "Wed, 21 Oct 2015 07:28:00 GMT", NOW
        )).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void rejectsInvalidRetryAfterAndClampsPastDates() {
        assertThat(StandardWebPushGateway.parseRetryAfter("not-a-date", NOW)).isNull();
        assertThat(StandardWebPushGateway.parseRetryAfter(
            "Wed, 21 Oct 2015 07:00:00 GMT", NOW
        )).isEqualTo(Duration.ofSeconds(1));
    }
}
