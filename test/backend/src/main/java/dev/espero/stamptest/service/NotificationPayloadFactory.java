package dev.espero.stamptest.service;

import dev.espero.stamptest.config.ClockTestProperties;
import dev.espero.stamptest.domain.DomainModels.NotificationEvent;
import dev.espero.stamptest.domain.DomainModels.NotificationKind;
import dev.espero.stamptest.domain.DomainModels.PushSubscription;
import dev.espero.stamptest.support.TokenSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationPayloadFactory {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final MessageSource messages;
    private final ClockTestProperties properties;
    private final TokenSupport tokens;

    public NotificationPayloadFactory(
        ObjectMapper objectMapper,
        MessageSource messages,
        ClockTestProperties properties,
        TokenSupport tokens
    ) {
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.properties = properties;
        this.tokens = tokens;
    }

    public String create(NotificationEvent event, PushSubscription subscription, Instant sentAt) {
        Locale locale = Locale.forLanguageTag(subscription.locale());
        OffsetDateTime scheduledKst = OffsetDateTime.ofInstant(event.scheduledAt(), properties.zone());
        OffsetDateTime sentKst = OffsetDateTime.ofInstant(sentAt, properties.zone());
        String title;
        String body;
        if (event.kind() == NotificationKind.CLOCK) {
            title = messages.getMessage(
                "push.clock.title",
                new Object[]{event.sequence(), properties.notificationCount()},
                locale
            );
            body = messages.getMessage("push.clock.body", new Object[]{
                event.sequence(),
                DISPLAY_TIME.format(scheduledKst),
                DISPLAY_TIME.format(sentKst),
                properties.zone().getId()
            }, locale);
        } else {
            title = messages.getMessage("push.test.title", null, locale);
            body = messages.getMessage(
                "push.test.body",
                new Object[]{DISPLAY_TIME.format(sentKst), properties.zone().getId()},
                locale
            );
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", "/?notificationId=" + event.id());
        data.put("notificationId", event.id().toString());
        data.put("messageId", event.messageId());

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", title);
        notification.put("body", body);
        notification.put("tag", event.notificationTag());
        notification.put("renotify", true);
        notification.put("icon", "/icon-192.png");
        notification.put("badge", "/icon-maskable-512.png");
        notification.put("data", data);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.kind().name());
        payload.put("notificationId", event.id().toString());
        payload.put("messageId", event.messageId());
        payload.put("ackToken", tokens.ackToken(event.id()));
        payload.put("sequence", event.sequence());
        payload.put("scheduledAt", scheduledKst);
        payload.put("sentAt", sentKst);
        payload.put("title", title);
        payload.put("body", body);
        payload.put("tag", event.notificationTag());
        payload.put("renotify", true);
        payload.put("icon", "/icon-192.png");
        payload.put("badge", "/icon-maskable-512.png");
        payload.put("data", data);
        payload.put("notification", notification);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize notification payload.", exception);
        }
    }
}
