package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.CatalogIntegrityException;
import dev.espero.festival.persistence.PerformanceCatalogReadStore;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.Artist;
import dev.espero.festival.persistence.PerformanceCatalogReadStore.PerformanceItem;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public API v2 reads for the revision-scoped performance catalog. */
@RestController
@Profile("db")
@RequestMapping("/api/v2")
public class PerformanceController {

    private static final String DEFAULT_CATEGORY = "ARTIST";
    private static final Set<String> CATEGORIES = Set.of("ARTIST", "CONTEST");
    private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final DateTimeFormatter AXIS_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final CatalogSnapshotProvider snapshots;
    private final PerformanceCatalogReadStore store;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;

    public PerformanceController(
        CatalogSnapshotProvider snapshots,
        PerformanceCatalogReadStore store,
        ApiMetaSupport metaSupport,
        Clock clock
    ) {
        this.snapshots = snapshots;
        this.store = store;
        this.metaSupport = metaSupport;
        this.clock = clock;
    }

    @GetMapping("/lineup")
    public ApiResponse<PerformanceResponses.Lineup> getLineup(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshot(request);
        String locale = validateQuery(request, Set.of("locale", "date", "category"));
        String category = valueOrDefault(request, "category", DEFAULT_CATEGORY);
        if (!CATEGORIES.contains(category)) {
            throw invalidQuery();
        }

        try {
            List<LocalDate> festivalDates = store.festivalDates(snapshot.context().revisionId());
            LocalDate date = requestedDate(request, festivalDates, snapshot);
            List<PerformanceCatalogReadStore.LineupItem> storedItems = store.lineup(
                snapshot.context().revisionId(), date, category, locale
            );
            List<PerformanceResponses.LineupItem> items = java.util.stream.IntStream.range(0, storedItems.size())
                .mapToObj(index -> lineupItem(storedItems.get(index), index + 1))
                .toList();
            return new ApiResponse<>(
                new PerformanceResponses.Lineup(date, category, items),
                metaSupport.meta(request, snapshot.context(), locale)
            );
        } catch (TransientDataAccessException | DataAccessResourceFailureException exception) {
            throw serviceUnavailable();
        }
    }

    @GetMapping("/artists/{artistId}")
    public ApiResponse<PerformanceResponses.Artist> getArtist(
        @PathVariable String artistId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request);
        String locale = validateQuery(request, Set.of("locale"));
        if (!PUBLIC_ID.matcher(artistId).matches()) {
            throw invalidQuery();
        }

        try {
            Artist artist = store.findArtist(snapshot.context().revisionId(), artistId, locale)
                .orElseThrow(this::notFound);
            return new ApiResponse<>(
                artistResponse(artist, snapshot),
                metaSupport.meta(request, snapshot.context(), locale)
            );
        } catch (TransientDataAccessException | DataAccessResourceFailureException exception) {
            throw serviceUnavailable();
        }
    }

    @GetMapping("/timetable")
    public ApiResponse<PerformanceResponses.Timetable> getTimetable(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshot(request);
        String locale = validateQuery(request, Set.of("locale"));

        try {
            PerformanceCatalogReadStore.Timetable timetable = store.timetable(
                snapshot.context().revisionId(), locale
            );
            return new ApiResponse<>(
                timetableResponse(timetable, snapshot),
                metaSupport.meta(request, snapshot.context(), locale)
            );
        } catch (TransientDataAccessException | DataAccessResourceFailureException exception) {
            throw serviceUnavailable();
        }
    }

    @GetMapping("/performances/{performanceId}")
    public ApiResponse<PerformanceResponses.PerformanceItem> getPerformance(
        @PathVariable String performanceId,
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshot(request);
        String locale = validateQuery(request, Set.of("locale"));
        if (!PUBLIC_ID.matcher(performanceId).matches()) {
            throw invalidQuery();
        }

        try {
            PerformanceItem performance = store.findPerformance(
                snapshot.context().revisionId(), performanceId, locale
            ).orElseThrow(this::notFound);
            return new ApiResponse<>(
                performanceResponse(performance, snapshot),
                metaSupport.meta(request, snapshot.context(), locale)
            );
        } catch (TransientDataAccessException | DataAccessResourceFailureException exception) {
            throw serviceUnavailable();
        }
    }

    @GetMapping("/prohibited-items")
    public ApiResponse<PerformanceResponses.ProhibitedItems> getProhibitedItems(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshot(request);
        String locale = validateQuery(request, Set.of("locale"));

        try {
            PerformanceCatalogReadStore.ProhibitedItems content = store.prohibitedItems(
                snapshot.context().revisionId(), locale
            );
            return new ApiResponse<>(
                new PerformanceResponses.ProhibitedItems(content.items(), content.message()),
                metaSupport.meta(request, snapshot.context(), locale)
            );
        } catch (TransientDataAccessException | DataAccessResourceFailureException exception) {
            throw serviceUnavailable();
        }
    }

    private CatalogSnapshot snapshot(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshots.required();
        metaSupport.setContext(request, snapshot.context(), PublicContentLocale.KOREAN);
        return snapshot;
    }

    private String validateQuery(HttpServletRequest request, Set<String> allowed) {
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw invalidQuery();
            }
        }
        return PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
    }

    private LocalDate requestedDate(
        HttpServletRequest request,
        List<LocalDate> festivalDates,
        CatalogSnapshot snapshot
    ) {
        String requested = request.getParameter("date");
        if (requested != null) {
            if (requested.isBlank()) {
                throw invalidQuery();
            }
            LocalDate parsed;
            try {
                parsed = LocalDate.parse(requested);
            } catch (DateTimeException exception) {
                throw invalidQuery();
            }
            if (!festivalDates.contains(parsed)) {
                throw invalidDate();
            }
            return parsed;
        }
        if (festivalDates.isEmpty()) {
            throw new CatalogIntegrityException("Published catalog has no FestivalDay for lineup defaults.");
        }
        LocalDate today = LocalDate.now(clock.withZone(snapshot.context().timezone()));
        return festivalDates.stream()
            .filter(date -> !date.isBefore(today))
            .findFirst()
            .orElse(festivalDates.getLast());
    }

    private String valueOrDefault(HttpServletRequest request, String name, String defaultValue) {
        String value = request.getParameter(name);
        if (value == null) {
            return defaultValue;
        }
        if (value.isBlank()) {
            throw invalidQuery();
        }
        return value;
    }

    private PerformanceResponses.Artist artistResponse(Artist artist, CatalogSnapshot snapshot) {
        List<PerformanceResponses.Link> socialLinks = artist.socialLinks().stream()
            .map(link -> new PerformanceResponses.Link(link.label(), link.url(), "_blank"))
            .toList();
        List<PerformanceResponses.Link> songs = artist.songs().stream()
            .map(song -> new PerformanceResponses.Link(song.label(), song.url(), "_blank"))
            .toList();
        List<PerformanceResponses.ArtistPerformance> performances = artist.performances().stream()
            .map(performance -> new PerformanceResponses.ArtistPerformance(
                performance.id(),
                performance.date(),
                inFestivalTimezone(performance.startsAt(), snapshot),
                inFestivalTimezone(performance.endsAt(), snapshot)
            ))
            .toList();
        return new PerformanceResponses.Artist(
            artist.id(),
            artist.category(),
            artist.name(),
            image(artist.image()),
            artist.introduction(),
            socialLinks,
            songs,
            performances
        );
    }

    private PerformanceResponses.Timetable timetableResponse(
        PerformanceCatalogReadStore.Timetable timetable,
        CatalogSnapshot snapshot
    ) {
        return new PerformanceResponses.Timetable(
            timetable.dates(),
            new PerformanceResponses.TimetableAxis(
                AXIS_TIME.format(timetable.axis().startTime()),
                AXIS_TIME.format(timetable.axis().endTime())
            ),
            timetable.items().stream()
                .map(item -> performanceResponse(item, snapshot))
                .toList()
        );
    }

    private PerformanceResponses.PerformanceItem performanceResponse(
        PerformanceItem performance,
        CatalogSnapshot snapshot
    ) {
        return new PerformanceResponses.PerformanceItem(
            performance.id(),
            performance.date(),
            performance.title(),
            performance.artists().stream()
                .map(artist -> new PerformanceResponses.PerformanceArtist(artist.id(), artist.name()))
                .toList(),
            inFestivalTimezone(performance.startsAt(), snapshot),
            inFestivalTimezone(performance.endsAt(), snapshot),
            performance.description()
        );
    }

    private OffsetDateTime inFestivalTimezone(OffsetDateTime value, CatalogSnapshot snapshot) {
        return value.atZoneSameInstant(snapshot.context().timezone())
            .toOffsetDateTime()
            .truncatedTo(ChronoUnit.MILLIS);
    }

    private static PerformanceResponses.LineupItem lineupItem(
        PerformanceCatalogReadStore.LineupItem item,
        int order
    ) {
        return new PerformanceResponses.LineupItem(
            item.artistId(), item.performanceId(), item.name(), image(item.image()), order
        );
    }

    private static PerformanceResponses.Image image(PerformanceCatalogReadStore.Image image) {
        return new PerformanceResponses.Image(image.url(), image.alt(), image.width(), image.height());
    }

    private ApiException invalidQuery() {
        return PublicContentLocale.invalidQuery();
    }

    private ApiException invalidDate() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "축제 날짜를 확인해 주세요.", false);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", false);
    }

    private ApiException serviceUnavailable() {
        return new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SERVICE_UNAVAILABLE",
            "일시적으로 정보를 불러올 수 없습니다.",
            true
        );
    }
}
