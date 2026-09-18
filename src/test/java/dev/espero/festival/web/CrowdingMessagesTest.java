package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class CrowdingMessagesTest {

    private static final LocalTime OPENS_AT = LocalTime.of(13, 0);

    @Test
    void everySupportedLocaleHasAMessageForEveryStatus() {
        for (String locale : new String[] {"ko", "en", "zh-Hans"}) {
            for (CrowdingResponse.Status status : CrowdingResponse.Status.values()) {
                assertThat(CrowdingMessages.message(status, OPENS_AT, locale)).as(locale + "/" + status).isNotBlank();
            }
        }
    }

    @Test
    void usesTheApprovedModerateWordingInEveryLocale() {
        assertThat(CrowdingMessages.message(CrowdingResponse.Status.MODERATE, OPENS_AT, "ko"))
            .isEqualTo("재학생존의 공간이 절반 이상 찼어요.");
        assertThat(CrowdingMessages.message(CrowdingResponse.Status.MODERATE, OPENS_AT, "en"))
            .isEqualTo("At least half full");
        assertThat(CrowdingMessages.message(CrowdingResponse.Status.MODERATE, OPENS_AT, "zh-Hans"))
            .isEqualTo("已占用一半以上");
    }

    @Test
    void insertsTheOpeningTimeIntoTheBeforeOpenMessage() {
        assertThat(CrowdingMessages.message(CrowdingResponse.Status.BEFORE_OPEN, OPENS_AT, "ko"))
            .isEqualTo("오늘 재학생존 입장은 13:00에 시작해요");
        assertThat(CrowdingMessages.message(CrowdingResponse.Status.BEFORE_OPEN, OPENS_AT, "en"))
            .isEqualTo("Student Zone entry starts at 13:00 today");
        assertThat(CrowdingMessages.message(CrowdingResponse.Status.BEFORE_OPEN, OPENS_AT, "zh-Hans"))
            .isEqualTo("今日学生区13:00开放入场");
    }

    @Test
    void refusesALocaleWithoutApprovedMessages() {
        assertThatThrownBy(() -> CrowdingMessages.message(CrowdingResponse.Status.FULL, OPENS_AT, "ja"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
