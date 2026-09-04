package dev.espero.stamptest.push;

import dev.espero.stamptest.config.PushProperties;
import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

@Component
public class StandardWebPushGateway implements PushGateway {

    private final PushProperties properties;

    public StandardWebPushGateway(PushProperties properties) {
        this.properties = properties;
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    public PushResult send(PushSubscription subscription, String payload, Duration ttl) throws Exception {
        if (!properties.configured()) {
            throw new PushConfigurationException("Standard Web Push is not configured.");
        }
        PushService service = new PushService(
            properties.publicKey(),
            properties.privateKey(),
            properties.subject()
        );
        Notification notification = Notification.builder()
            .endpoint(subscription.endpoint())
            .userPublicKey(subscription.p256dh())
            .userAuth(subscription.auth())
            .payload(payload)
            .ttl(Math.toIntExact(ttl.toSeconds()))
            .urgency(Urgency.NORMAL)
            .build();
        Future<HttpResponse> pending = service.sendAsync(notification, Encoding.AES128GCM);
        HttpResponse response;
        Duration timeout = properties.requestTimeout().compareTo(ttl) < 0 ? properties.requestTimeout() : ttl;
        try {
            response = pending.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new PushRequestTimeoutException(
                "Web Push provider did not respond within " + timeout,
                exception
            );
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        }
        return new PushResult(
            response.getStatusLine().getStatusCode(),
            retryAfter(response.getFirstHeader("Retry-After"))
        );
    }

    private Duration retryAfter(Header header) {
        if (header == null) {
            return null;
        }
        return parseRetryAfter(header.getValue(), Instant.now());
    }

    static Duration parseRetryAfter(String rawValue, Instant now) {
        if (rawValue == null || now == null) {
            return null;
        }
        String value = rawValue.trim();
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(value)));
        } catch (NumberFormatException exception) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.ofSeconds(Math.max(1, Duration.between(now, retryAt).toSeconds()));
            } catch (DateTimeParseException invalidHttpDate) {
                return null;
            }
        }
    }
}
