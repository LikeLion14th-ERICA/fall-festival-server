package dev.espero.festival;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CatalogManifestValidatorTest {

    private static final LocalDate FESTIVAL_DATE = LocalDate.of(2030, 10, 1);
    private final CatalogManifestValidator validator = new CatalogManifestValidator();

    @Test
    void acceptsEmptyAndCompletePerformanceCatalogs() {
        assertThatCode(() -> validator.validate(emptyManifest())).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(new PerformanceFixture().manifest()))
            .doesNotThrowAnyException();
    }

    @Test
    void acceptsProductPermittedSparseRelationshipsAndOvernightPerformance() {
        PerformanceFixture fixture = new PerformanceFixture();
        fixture.performanceArtists.clear();
        fixture.artists.add(new CatalogManifest.Artist(
            "unassigned", "CONTEST", "/artists/unassigned.png", 10, 10
        ));
        fixture.artistTranslations.add(new CatalogManifest.ArtistTranslation(
            "unassigned", "ko", "미배정", "미배정", null
        ));

        assertThatCode(() -> validator.validate(fixture.manifest())).doesNotThrowAnyException();
    }

    @Test
    void validatesArtistAndArtistTranslations() {
        assertInvalid(fixture -> fixture.artists.add(fixture.artists.getFirst()), "Duplicate artists id");
        assertInvalid(fixture -> fixture.artists.set(0,
            new CatalogManifest.Artist("artist-one", "GUEST", "/a.png", 10, 10)),
            "Unsupported artist category");
        assertInvalid(fixture -> fixture.artists.set(0,
            new CatalogManifest.Artist("artist-one", "ARTIST", "/a.png", 0, 10)),
            "dimensions must be positive");
        assertInvalid(fixture -> fixture.artistTranslations.set(0,
            new CatalogManifest.ArtistTranslation("missing", "ko", "이름", "대체", null)),
            "unknown artist");
        assertInvalid(fixture -> fixture.artistTranslations.clear(),
            "Every artist needs a Korean");
    }

    @Test
    void validatesArtistLinksAndTheirKoreanTranslations() {
        assertInvalid(fixture -> fixture.artistLinks.set(0,
            new CatalogManifest.ArtistLink("missing", 1, "https://example.test")),
            "unknown artist");
        assertInvalid(fixture -> fixture.artistLinks.add(
            new CatalogManifest.ArtistLink("artist-one", 1, "https://example.test/duplicate")),
            "Duplicate artistLinks");
        assertInvalid(fixture -> fixture.artistLinks.set(0,
            new CatalogManifest.ArtistLink("artist-one", 1, "http://example.test")),
            "must use https://");
        assertInvalid(fixture -> fixture.artistLinkTranslations.clear(),
            "Every artist link needs a Korean");
    }

    @Test
    void validatesArtistSongsWithoutRequiringContiguousOrders() {
        assertInvalid(fixture -> fixture.artistSongs.set(0,
            new CatalogManifest.ArtistSong("artist-one", 0, "https://example.test/zero")),
            "between 1 and 3");
        assertInvalid(fixture -> fixture.artistSongs.set(0,
            new CatalogManifest.ArtistSong("artist-one", 4, "https://example.test/four")),
            "between 1 and 3");
        assertInvalid(fixture -> fixture.artistSongs.add(
            new CatalogManifest.ArtistSong("artist-one", 1, "https://example.test/duplicate")),
            "Duplicate artistSongs");
        assertInvalid(fixture -> fixture.artistSongs.set(0,
            new CatalogManifest.ArtistSong("artist-one", 1, "http://example.test/song")),
            "must use https://");
        assertInvalid(fixture -> fixture.artistSongTranslations.clear(),
            "Every artist song needs a Korean");
    }

    @Test
    void validatesPerformancesAgainstFestivalDaysAndKoreanTranslations() {
        assertInvalid(fixture -> fixture.performances.add(fixture.performances.getFirst()),
            "Duplicate performances id");
        assertInvalid(fixture -> fixture.performances.set(0, new CatalogManifest.Performance(
            "performance-one", FESTIVAL_DATE.plusDays(1),
            at("2030-10-02T19:00:00+09:00"), at("2030-10-02T20:00:00+09:00"))),
            "unknown festival day");
        assertInvalid(fixture -> fixture.performances.set(0, new CatalogManifest.Performance(
            "performance-one", FESTIVAL_DATE,
            at("2030-10-01T20:00:00+09:00"), at("2030-10-01T20:00:00+09:00"))),
            "must be before endsAt");
        assertInvalid(fixture -> fixture.performances.set(0, new CatalogManifest.Performance(
            "performance-one", FESTIVAL_DATE,
            at("2030-10-01T15:30:00-07:00"), at("2030-10-01T16:30:00-07:00"))),
            "must fall on festivalDate");
        assertInvalid(fixture -> fixture.performanceTranslations.set(0,
            new CatalogManifest.PerformanceTranslation("missing", "ko", "공연", null)),
            "unknown performance");
        assertInvalid(fixture -> fixture.performanceTranslations.clear(),
            "Every performance needs a Korean");
    }

    @Test
    void validatesPerformanceArtistRelationshipUniquenessAndOrder() {
        assertInvalid(fixture -> fixture.performanceArtists.set(0,
            new CatalogManifest.PerformanceArtist("missing", "artist-one", 1)),
            "unknown performance");
        assertInvalid(fixture -> fixture.performanceArtists.set(0,
            new CatalogManifest.PerformanceArtist("performance-one", "missing", 1)),
            "unknown artist");
        assertInvalid(fixture -> fixture.performanceArtists.add(
            new CatalogManifest.PerformanceArtist("performance-one", "artist-one", 2)),
            "Duplicate performanceArtists artist");
        assertInvalid(fixture -> {
            fixture.artists.add(new CatalogManifest.Artist(
                "artist-two", "ARTIST", "/artists/two.png", 10, 10
            ));
            fixture.artistTranslations.add(new CatalogManifest.ArtistTranslation(
                "artist-two", "ko", "둘", "둘", null
            ));
            fixture.performanceArtists.add(new CatalogManifest.PerformanceArtist(
                "performance-one", "artist-two", 1
            ));
        }, "Duplicate performanceArtists displayOrder");
        assertInvalid(fixture -> fixture.performanceArtists.set(0,
            new CatalogManifest.PerformanceArtist("performance-one", "artist-one", 0)),
            "must be positive");
    }

    @Test
    void validatesTimetableAndProhibitedContent() {
        assertInvalid(fixture -> fixture.timetableConfig = new CatalogManifest.TimetableConfig(
            LocalTime.of(22, 0), LocalTime.of(17, 0)), "must be before axisEndTime");
        assertInvalid(fixture -> fixture.prohibitedItems.add(fixture.prohibitedItems.getFirst()),
            "Duplicate prohibitedItems id");
        assertInvalid(fixture -> fixture.prohibitedItems.add(
            new CatalogManifest.ProhibitedItem("item-two", 1)),
            "Duplicate prohibitedItems.sortOrder");
        assertInvalid(fixture -> fixture.prohibitedItemTranslations.set(0,
            new CatalogManifest.ProhibitedItemTranslation("missing", "ko", "금지")),
            "unknown prohibited item");
        assertInvalid(fixture -> fixture.prohibitedItemTranslations.clear(),
            "Every prohibited item needs a Korean");
        assertInvalid(fixture -> fixture.prohibitedMessages.add(
            new CatalogManifest.ProhibitedMessage("ko", "중복")),
            "Duplicate prohibitedMessages locale");
        assertInvalid(fixture -> fixture.prohibitedMessages.set(0,
            new CatalogManifest.ProhibitedMessage("ko", " ")), "must not be blank");
        assertInvalid(fixture -> fixture.prohibitedMessages.set(0,
            new CatalogManifest.ProhibitedMessage("en", "No entry")),
            "needs a Korean row");
    }

    @Test
    void rejectsUnsupportedPerformanceLocale() {
        assertInvalid(fixture -> fixture.artistTranslations.set(0,
            new CatalogManifest.ArtistTranslation("artist-one", "fr", "Nom", "Image", null)),
            "Unsupported locale");
    }

    @Test
    void requiresKoreanContentAndLocaleSpecificSortOrderForEachSpace() {
        CatalogManifest manifest = new PerformanceFixture().manifestWithSpace(
            new CatalogManifest.Space("space-one", "BOOTH", "/spaces/one.png", 100, 100)
        );

        assertThatThrownBy(() -> validator.validate(manifest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Every entity needs a Korean spaceTranslations row");
    }

    @Test
    void rejectsPartialTicketScheduleBeforeItCanBecomeADraft() {
        CatalogManifest.TicketGuide partialSchedule = new CatalogManifest.TicketGuide(
            null, null, null, null, null, List.of(),
            LocalDate.of(2030, 10, 1), null, null, null, null, null
        );
        CatalogManifest manifest = emptyManifest(partialSchedule, stampGuide());

        assertThatThrownBy(() -> validator.validate(manifest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ticketGuide schedule fields must be complete");
    }

    @Test
    void rejectsBlankOptionalStampGuideTextBeforeItCanBecomeADraft() {
        CatalogManifest.StampGuide invalid = new CatalogManifest.StampGuide(
            "스탬프투어", List.of(), List.of("안내"), "기념품", "", null, "수량 소진 시 종료", null
        );

        assertThatThrownBy(() -> validator.validate(emptyManifest(ticketGuide(), invalid)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stampGuide.rewardLocationText");
    }

    private void assertInvalid(Consumer<PerformanceFixture> mutation, String message) {
        PerformanceFixture fixture = new PerformanceFixture();
        mutation.accept(fixture);
        assertThatThrownBy(() -> validator.validate(fixture.manifest()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(message);
    }

    private CatalogManifest emptyManifest() {
        return emptyManifest(ticketGuide(), stampGuide());
    }

    private CatalogManifest emptyManifest(
        CatalogManifest.TicketGuide ticketGuide,
        CatalogManifest.StampGuide stampGuide
    ) {
        return new CatalogManifest(
            UUID.randomUUID(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(), ticketGuide, stampGuide
        );
    }

    private CatalogManifest.TicketGuide ticketGuide() {
        return new CatalogManifest.TicketGuide(
            15000, null, null, null, null,
            List.of("안내"), null, null, null, null, null, null
        );
    }

    private CatalogManifest.StampGuide stampGuide() {
        return new CatalogManifest.StampGuide(
            "스탬프투어", List.of(), List.of("안내"), "기념품", null, null, "수량 소진 시 종료", null
        );
    }

    private static OffsetDateTime at(String value) {
        return OffsetDateTime.parse(value);
    }

    private final class PerformanceFixture {
        private final List<CatalogManifest.Artist> artists = new ArrayList<>(List.of(
            new CatalogManifest.Artist("artist-one", "ARTIST", "/artists/one.png", 100, 100)
        ));
        private final List<CatalogManifest.ArtistTranslation> artistTranslations = new ArrayList<>(List.of(
            new CatalogManifest.ArtistTranslation("artist-one", "ko", "가수", "가수 사진", null)
        ));
        private final List<CatalogManifest.ArtistLink> artistLinks = new ArrayList<>(List.of(
            new CatalogManifest.ArtistLink("artist-one", 1, "https://example.test/artist")
        ));
        private final List<CatalogManifest.ArtistLinkTranslation> artistLinkTranslations =
            new ArrayList<>(List.of(
                new CatalogManifest.ArtistLinkTranslation("artist-one", 1, "ko", "공식 링크")
            ));
        private final List<CatalogManifest.ArtistSong> artistSongs = new ArrayList<>(List.of(
            new CatalogManifest.ArtistSong("artist-one", 1, "https://example.test/song-one"),
            new CatalogManifest.ArtistSong("artist-one", 3, "https://example.test/song-three")
        ));
        private final List<CatalogManifest.ArtistSongTranslation> artistSongTranslations =
            new ArrayList<>(List.of(
                new CatalogManifest.ArtistSongTranslation("artist-one", 1, "ko", "노래 하나"),
                new CatalogManifest.ArtistSongTranslation("artist-one", 3, "ko", "노래 셋")
            ));
        private final List<CatalogManifest.Performance> performances = new ArrayList<>(List.of(
            new CatalogManifest.Performance(
                "performance-one", FESTIVAL_DATE,
                at("2030-10-01T23:00:00+09:00"), at("2030-10-02T00:30:00+09:00")
            )
        ));
        private final List<CatalogManifest.PerformanceTranslation> performanceTranslations =
            new ArrayList<>(List.of(
                new CatalogManifest.PerformanceTranslation("performance-one", "ko", "공연", null)
            ));
        private final List<CatalogManifest.PerformanceArtist> performanceArtists =
            new ArrayList<>(List.of(
                new CatalogManifest.PerformanceArtist("performance-one", "artist-one", 1)
            ));
        private CatalogManifest.TimetableConfig timetableConfig = new CatalogManifest.TimetableConfig(
            LocalTime.of(17, 0), LocalTime.of(23, 59)
        );
        private final List<CatalogManifest.ProhibitedItem> prohibitedItems = new ArrayList<>(List.of(
            new CatalogManifest.ProhibitedItem("item-one", 1)
        ));
        private final List<CatalogManifest.ProhibitedItemTranslation> prohibitedItemTranslations =
            new ArrayList<>(List.of(
                new CatalogManifest.ProhibitedItemTranslation("item-one", "ko", "반입 금지")
            ));
        private final List<CatalogManifest.ProhibitedMessage> prohibitedMessages = new ArrayList<>(List.of(
            new CatalogManifest.ProhibitedMessage("ko", "반입할 수 없습니다")
        ));

        private CatalogManifest manifest() {
            return manifestWithSpace(null);
        }

        private CatalogManifest manifestWithSpace(CatalogManifest.Space space) {
            List<CatalogManifest.Space> spaces = space == null ? List.of() : List.of(space);
            return new CatalogManifest(
                UUID.randomUUID(),
                List.of(new CatalogManifest.FestivalDay(
                    FESTIVAL_DATE,
                    at("2030-10-01T10:00:00+09:00"),
                    at("2030-10-02T02:00:00+09:00")
                )),
                spaces, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                artists, artistTranslations, artistLinks, artistLinkTranslations,
                artistSongs, artistSongTranslations, performances, performanceTranslations,
                performanceArtists, timetableConfig, prohibitedItems,
                prohibitedItemTranslations, prohibitedMessages, ticketGuide(), stampGuide()
            );
        }
    }
}
