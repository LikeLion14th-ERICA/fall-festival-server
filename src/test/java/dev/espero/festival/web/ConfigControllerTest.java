package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.CatalogSnapshot.HomeLink;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConfigControllerTest {

    private static final List<LocalDate> DATES = List.of(
        LocalDate.parse("2030-10-01"), LocalDate.parse("2030-10-02"), LocalDate.parse("2030-10-04")
    );

    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);

    @Test
    void servesTheTitleDatesLanguagesAndOrderedLinksOfThePublishedRevision() throws Exception {
        when(snapshots.required()).thenReturn(snapshot("한양문화제 동심", List.of(
            new HomeLink("youtube", "OFFICIAL_CHANNEL", "YouTube", "https://example.test/yt", "youtube", 2),
            new HomeLink("faq", "FAQ", "FAQ", "https://example.test/faq", null, 1),
            new HomeLink("instagram", "OFFICIAL_CHANNEL", "Instagram", "https://example.test/ig", "instagram", 1),
            new HomeLink("notices", "UNIVERSITY_NOTICES", "공지사항", "https://example.test/notices", null, 1)
        )));

        mvc("2030-09-20T03:00:00Z").perform(get("/api/v2/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.festival.title").value("한양문화제 동심"))
            .andExpect(jsonPath("$.data.festival.dates", Matchers.contains("2030-10-01", "2030-10-02", "2030-10-04")))
            .andExpect(jsonPath("$.data.festival.defaultDate").value("2030-10-01"))
            .andExpect(jsonPath("$.data.languages[0].code").value("ko"))
            .andExpect(jsonPath("$.data.languages[0].label").value("한국어"))
            .andExpect(jsonPath("$.data.languages.length()").value(1))
            .andExpect(jsonPath("$.data.links.universityNotices.url").value("https://example.test/notices"))
            .andExpect(jsonPath("$.data.links.universityNotices.target").value("_blank"))
            .andExpect(jsonPath("$.data.links.faq.label").value("FAQ"))
            .andExpect(jsonPath("$.data.links.officialChannels[*].id", Matchers.contains("instagram", "youtube")))
            .andExpect(jsonPath("$.data.links.officialChannels[0].iconKey").value("instagram"))
            .andExpect(jsonPath("$.meta.revision").value(3));
    }

    @Test
    void keepsLinksWithoutApprovedUrlsNullAndChannelsEmpty() throws Exception {
        when(snapshots.required()).thenReturn(snapshot("한양문화제 동심", List.of()));

        mvc("2030-10-02T03:00:00Z").perform(get("/api/v2/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.links.universityNotices").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.links.faq").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.data.links.officialChannels").isEmpty());
    }

    @Test
    void choosesTheDefaultDateForEachPartOfTheFestivalCalendar() {
        assertThat(ConfigController.defaultDate(DATES, LocalDate.parse("2030-09-30"))).isEqualTo("2030-10-01");
        assertThat(ConfigController.defaultDate(DATES, LocalDate.parse("2030-10-02"))).isEqualTo("2030-10-02");
        assertThat(ConfigController.defaultDate(DATES, LocalDate.parse("2030-10-03"))).isEqualTo("2030-10-04");
        assertThat(ConfigController.defaultDate(DATES, LocalDate.parse("2030-10-09"))).isEqualTo("2030-10-04");
        assertThat(ConfigController.defaultDate(List.of(), LocalDate.parse("2030-10-02"))).isNull();
    }

    @Test
    void rejectsUnpublishedLocalesUnknownQueriesAndAMissingHome() throws Exception {
        when(snapshots.required()).thenReturn(snapshot("한양문화제 동심", List.of()));
        MockMvc mvc = mvc("2030-10-02T03:00:00Z");

        mvc.perform(get("/api/v2/config").param("locale", "en"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("LOCALE_NOT_READY"));
        mvc.perform(get("/api/v2/config").param("festival", "other"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));

        when(snapshots.required()).thenReturn(snapshot(null, List.of()));
        mvc.perform(get("/api/v2/config"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void servesAPublishedLocaleFromItsOwnSnapshotAndListsItAfterKorean() throws Exception {
        when(snapshots.required()).thenReturn(snapshot("한양문화제 동심", List.of()));
        when(snapshots.required("en")).thenReturn(snapshot("Hanyang Festival Dongsim", List.of(
            new HomeLink("notices", "UNIVERSITY_NOTICES", "Notices", "https://example.test/notices", null, 1)
        )));
        when(snapshots.publishedLocales()).thenReturn(List.of("ko", "en"));
        MockMvc mvc = mvc("2030-10-02T03:00:00Z");

        mvc.perform(get("/api/v2/config").param("locale", "en"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.festival.title").value("Hanyang Festival Dongsim"))
            .andExpect(jsonPath("$.data.links.universityNotices.label").value("Notices"))
            .andExpect(jsonPath("$.data.languages[*].code", Matchers.contains("ko", "en")))
            .andExpect(jsonPath("$.data.languages[1].label").value("English"))
            .andExpect(jsonPath("$.meta.locale").value("en"));
        mvc.perform(get("/api/v2/config").param("locale", "zh-Hans"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("LOCALE_NOT_READY"));
    }

    private MockMvc mvc(String now) {
        Clock clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC);
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
        return MockMvcBuilders.standaloneSetup(new ConfigController(snapshots, metaSupport, clock))
            .setControllerAdvice(new GlobalApiExceptionHandler(metaSupport))
            .build();
    }

    private static CatalogSnapshot snapshot(String title, List<HomeLink> links) {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                ApiMetaTestFixtures.FESTIVAL_ID.toString(), UUID.fromString("00000000-0000-0000-0000-000000000003"), 3
            ),
            List.of(), List.of(), List.of(), Map.of(), null, null, null,
            title == null ? CatalogSnapshot.FestivalHome.EMPTY : new CatalogSnapshot.FestivalHome(title, DATES, links)
        );
    }
}
