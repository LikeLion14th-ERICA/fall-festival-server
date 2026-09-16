package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogManifestValidatorTest {

    private final CatalogManifestValidator validator = new CatalogManifestValidator();

    @Test
    void acceptsACompleteEmptyCatalogWithGuides() {
        assertThatCode(() -> validator.validate(emptyManifest())).doesNotThrowAnyException();
    }

    @Test
    void requiresKoreanContentAndLocaleSpecificSortOrderForEachSpace() {
        CatalogManifest manifest = new CatalogManifest(
            UUID.randomUUID(),
            List.of(),
            List.of(new CatalogManifest.Space("space-one", "BOOTH", "/spaces/one.png", 100, 100)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            ticketGuide(),
            stampGuide()
        );

        assertThatThrownBy(() -> validator.validate(manifest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Every entity needs a Korean spaceTranslations row");
    }

    @Test
    void rejectsPartialTicketScheduleBeforeItCanBecomeADraft() {
        CatalogManifest.TicketGuide partialSchedule = new CatalogManifest.TicketGuide(
            null, null, null, null, null, null, null, null, null, null, List.of(),
            LocalDate.of(2030, 10, 1), null, null, null, null, null
        );
        CatalogManifest manifest = new CatalogManifest(
            UUID.randomUUID(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), partialSchedule, stampGuide()
        );

        assertThatThrownBy(() -> validator.validate(manifest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ticketGuide schedule fields must be complete");
    }

    @Test
    void rejectsBlankOptionalStampGuideTextBeforeItCanBecomeADraft() {
        CatalogManifest.StampGuide invalid = new CatalogManifest.StampGuide(
            "스탬프투어", List.of(), List.of("안내"), "기념품", "", null, "수량 소진 시 종료", null
        );

        assertThatThrownBy(() -> validator.validate(emptyManifest(invalid)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stampGuide.rewardLocationText");
    }

    private CatalogManifest emptyManifest() {
        return emptyManifest(stampGuide());
    }

    private CatalogManifest emptyManifest(CatalogManifest.StampGuide stampGuide) {
        return new CatalogManifest(
            UUID.randomUUID(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), ticketGuide(), stampGuide
        );
    }

    private CatalogManifest.TicketGuide ticketGuide() {
        return new CatalogManifest.TicketGuide(
            15000, null, null, null, null, null, null, null, null, null,
            List.of("안내"), null, null, null, null, null, null
        );
    }

    private CatalogManifest.StampGuide stampGuide() {
        return new CatalogManifest.StampGuide(
            "스탬프투어", List.of(), List.of("안내"), "기념품", null, null, "수량 소진 시 종료", null
        );
    }
}
