package dev.espero.festival.web;

import java.time.LocalTime;
import java.util.Map;

/**
 * Approved crowding messages per content locale.
 *
 * <p>English and Simplified Chinese come from the approved translation table
 * (docs/wiki/product/translations.md). They are served only after the locale
 * itself is published; until then every public request resolves to Korean.</p>
 */
final class CrowdingMessages {

    private static final Map<String, Map<CrowdingResponse.Status, String>> MESSAGES = Map.of(
        "ko", Map.of(
            CrowdingResponse.Status.BEFORE_OPEN, "오늘 재학생존 입장은 %s에 시작해요",
            CrowdingResponse.Status.RELAXED, "재학생존의 공간이 많이 남았어요.",
            CrowdingResponse.Status.MODERATE, "재학생존의 공간이 절반 이상 찼어요.",
            CrowdingResponse.Status.CROWDED, "재학생존이 많이 혼잡해요.",
            CrowdingResponse.Status.FULL, "재학생존이 꽉 차서 외부인존에서만 즐길 수 있어요.",
            CrowdingResponse.Status.CLOSED, "오늘 재학생존 운영이 종료됐어요"
        ),
        "en", Map.of(
            CrowdingResponse.Status.BEFORE_OPEN, "Student Zone entry starts at %s today",
            CrowdingResponse.Status.RELAXED, "Plenty of space available",
            CrowdingResponse.Status.MODERATE, "At least half full",
            CrowdingResponse.Status.CROWDED, "Very crowded",
            CrowdingResponse.Status.FULL, "The Student Zone is full. Please use the Visitor Zone.",
            CrowdingResponse.Status.CLOSED, "The Student Zone is closed for today"
        ),
        "zh-Hans", Map.of(
            CrowdingResponse.Status.BEFORE_OPEN, "今日学生区%s开放入场",
            CrowdingResponse.Status.RELAXED, "空间充足",
            CrowdingResponse.Status.MODERATE, "已占用一半以上",
            CrowdingResponse.Status.CROWDED, "非常拥挤",
            CrowdingResponse.Status.FULL, "本校学生区已满，请前往访客区。",
            CrowdingResponse.Status.CLOSED, "今日学生区已关闭"
        )
    );

    private CrowdingMessages() {}

    static String message(CrowdingResponse.Status status, LocalTime opensAt, String locale) {
        Map<CrowdingResponse.Status, String> messages = MESSAGES.get(locale);
        if (messages == null) {
            throw new IllegalArgumentException("No approved crowding messages for locale " + locale);
        }
        String message = messages.get(status);
        return status == CrowdingResponse.Status.BEFORE_OPEN ? message.formatted(opensAt) : message;
    }
}
