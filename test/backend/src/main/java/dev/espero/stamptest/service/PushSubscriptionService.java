package dev.espero.stamptest.service;

import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.persistence.PushSubscriptionStore;
import dev.espero.stamptest.support.TokenSupport;
import dev.espero.stamptest.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PushSubscriptionService {

    private static final Set<String> SUPPORTED_LOCALES = Set.of("ko", "en", "zh");

    private final PushSubscriptionStore store;
    private final PushEndpointValidator validator;
    private final TokenSupport tokens;
    private final Clock clock;

    public PushSubscriptionService(
        PushSubscriptionStore store,
        PushEndpointValidator validator,
        TokenSupport tokens,
        Clock clock
    ) {
        this.store = store;
        this.validator = validator;
        this.tokens = tokens;
        this.clock = clock;
    }

    public PushSubscription upsert(
        UUID participantId,
        String endpoint,
        String p256dh,
        String auth,
        Long expirationTimeMillis,
        String requestedLocale,
        String requestedTimeZone
    ) {
        validator.validate(endpoint, p256dh, auth);
        String locale = normalizeLocale(requestedLocale);
        String timeZone = normalizeTimeZone(requestedTimeZone);
        Instant expirationTime = expirationTimeMillis == null ? null : Instant.ofEpochMilli(expirationTimeMillis);
        Instant now = clock.instant();
        if (expirationTime != null && !expirationTime.isAfter(now)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "PUSH_SUBSCRIPTION_EXPIRED",
                "The push subscription is already expired."
            );
        }
        return store.upsert(
            participantId,
            endpoint,
            tokens.sha256(endpoint),
            p256dh,
            auth,
            expirationTime,
            locale,
            timeZone,
            now
        );
    }

    public void deactivate(UUID participantId, UUID subscriptionId) {
        if (!store.deactivate(participantId, subscriptionId, "USER_UNSUBSCRIBED", clock.instant())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PUSH_SUBSCRIPTION_NOT_FOUND", "Push subscription was not found.");
        }
    }

    private String normalizeLocale(String requested) {
        String value = requested == null || requested.isBlank()
            ? "ko"
            : Locale.forLanguageTag(requested).getLanguage();
        return SUPPORTED_LOCALES.contains(value) ? value : "ko";
    }

    private String normalizeTimeZone(String requested) {
        String value = requested == null || requested.isBlank() ? "Asia/Seoul" : requested;
        try {
            return ZoneId.of(value).getId();
        } catch (ZoneRulesException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_ZONE", "timeZone is not recognized.");
        }
    }
}
