package dev.espero.festival.web;

import dev.espero.festival.push.PushNotificationService;
import dev.espero.festival.push.PushSubscriptionFailedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous device-token registration for notice push. There is no account,
 * so every token joins one festival-wide topic (see PushNotificationService);
 * per-device unsubscribe is not modeled yet. The route name matches the
 * frontend's existing FCM client code (confirmed with 문제원, 2026-09-28)
 * rather than the internal push-* naming used elsewhere in this package.
 */
@RestController
@Profile("db")
@RequestMapping("/api/v2")
public class NotificationSubscriptionController {

    private static final int MAX_TOKEN_LENGTH = 4096;

    private final PushNotificationService push;
    private final ApiMetaSupport metaSupport;

    public NotificationSubscriptionController(PushNotificationService push, ApiMetaSupport metaSupport) {
        this.push = push;
        this.metaSupport = metaSupport;
    }

    @PostMapping(path = "/notification-subscriptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<NotificationSubscriptionResponse>> subscribe(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        validateQuery(request);
        String token = requireToken(body);
        try {
            push.subscribe(token);
        } catch (PushSubscriptionFailedException exception) {
            throw unavailable();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(
                new NotificationSubscriptionResponse(true), metaSupport.unscopedMeta(request, "ko")
            ));
    }

    private String requireToken(Map<String, Object> body) {
        Object value = body == null ? null : body.get("token");
        if (body == null || body.size() != 1 || !(value instanceof String token)
            || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 필드를 확인해 주세요.", false);
        }
        return token;
    }

    private void validateQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
        }
    }

    private ApiException unavailable() {
        return new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "일시적으로 요청을 처리할 수 없습니다.", true
        );
    }
}
