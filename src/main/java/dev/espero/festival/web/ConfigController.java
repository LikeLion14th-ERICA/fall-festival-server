package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.CatalogSnapshot.HomeLink;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v2/config: the home screen's festival title and days, the
 * published languages and the external home links (HOME-002, HOME-004,
 * HOME-005, HOME-008, WELCOME-001). Everything but the languages comes from
 * the published catalog revision.
 */
@RestController
@Profile("db")
@RequestMapping("/api/v2")
public class ConfigController {

    private static final String TARGET = "_blank";

    private final CatalogSnapshotProvider snapshots;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;

    public ConfigController(CatalogSnapshotProvider snapshots, ApiMetaSupport metaSupport, Clock clock) {
        this.snapshots = snapshots;
        this.metaSupport = metaSupport;
        this.clock = clock;
    }

    @GetMapping("/config")
    public ApiResponse<ConfigResponse> getConfig(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshots.required();
        metaSupport.setContext(request, snapshot.context(), PublicContentLocale.KOREAN);
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
        String locale = PublicContentLocale.requirePublishedLocale(request);

        CatalogSnapshot.FestivalHome home = snapshot.home();
        if (home.title() == null) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "일시적으로 정보를 불러올 수 없습니다.", true
            );
        }
        LocalDate today = LocalDate.now(clock.withZone(snapshot.context().timezone()));
        ConfigResponse response = new ConfigResponse(
            new ConfigResponse.Festival(
                snapshot.context().festivalId(),
                home.title(),
                home.dates(),
                defaultDate(home.dates(), today)
            ),
            PublicContentLocale.PUBLISHED_LANGUAGES.stream()
                .map(language -> new ConfigResponse.Language(language.getKey(), language.getValue()))
                .toList(),
            links(home.links())
        );
        return new ApiResponse<>(response, metaSupport.meta(request, snapshot.context(), locale));
    }

    /**
     * The first day before the festival, the last day after it, and otherwise
     * today, or the next festival day when today falls between two of them.
     */
    static LocalDate defaultDate(List<LocalDate> dates, LocalDate today) {
        if (dates.isEmpty()) {
            return null;
        }
        return dates.stream().filter(date -> !date.isBefore(today)).findFirst().orElse(dates.getLast());
    }

    private static ConfigResponse.Links links(List<HomeLink> links) {
        return new ConfigResponse.Links(
            single(links, "UNIVERSITY_NOTICES"),
            single(links, "FAQ"),
            links.stream()
                .filter(link -> link.kind().equals("OFFICIAL_CHANNEL"))
                .sorted(Comparator.comparingInt(HomeLink::sortOrder))
                .map(link -> new ConfigResponse.Channel(link.id(), link.label(), link.url(), TARGET, link.iconKey()))
                .toList(),
            single(links, "WELCOME_DAY")
        );
    }

    private static ConfigResponse.Link single(List<HomeLink> links, String kind) {
        return links.stream()
            .filter(link -> link.kind().equals(kind))
            .findFirst()
            .map(link -> new ConfigResponse.Link(link.label(), link.url(), TARGET))
            .orElse(null);
    }
}
