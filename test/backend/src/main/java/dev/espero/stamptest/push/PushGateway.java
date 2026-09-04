package dev.espero.stamptest.push;

import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import java.time.Duration;

public interface PushGateway {

    PushResult send(PushSubscription subscription, String payload, Duration ttl) throws Exception;

    record PushResult(int statusCode, Duration retryAfter) {}
}
