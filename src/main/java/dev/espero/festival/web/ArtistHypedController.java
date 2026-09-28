package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.ArtistHypedStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous, repeatable Hyped participation for published ARTIST entries. */
@RestController
@Profile("db")
@RequestMapping("/api/v2")
public class ArtistHypedController {

    private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final LocalDate REHEARSAL_DAY = LocalDate.of(2026, 9, 28);
    private static final LocalTime REHEARSAL_START = LocalTime.of(11, 0);
    private static final LocalTime REHEARSAL_END = LocalTime.of(15, 0);

    private final CatalogSnapshotProvider snapshots;
    private final ArtistHypedStore store;
    private final ArtistHypedBatchService batches;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;
    private CachedCounts cachedCounts;

    public ArtistHypedController(
        CatalogSnapshotProvider snapshots,
        ArtistHypedStore store,
        ArtistHypedBatchService batches,
        ApiMetaSupport metaSupport,
        Clock clock
    ) {
        this.snapshots = snapshots;
        this.store = store;
        this.batches = batches;
        this.metaSupport = metaSupport;
        this.clock = clock;
    }

    @GetMapping("/artist-hyped")
    public ResponseEntity<ApiResponse<ArtistHypedResponse.Summary>> current(HttpServletRequest request) {
        Context context = context(request);
        Instant now = clock.instant();
        try {
            String prefix = countPrefix(context.snapshot(), now);
            var items = currentCounts(countKey(context.snapshot(), prefix));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(
                    new ArtistHypedResponse.Summary(enabled(context.snapshot(), now), items),
                    metaSupport.dynamicMeta(request, context.snapshot().context(), context.locale())
                ));
        } catch (DataAccessException exception) {
            RequestDiagnostics.failure(request, exception);
            throw unavailable();
        }
    }

    @PostMapping(path = "/artists/{artistId}/hyped", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ArtistHypedResponse.Increment>> increment(
        @PathVariable String artistId,
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        Context context = context(request);
        if (!PUBLIC_ID.matcher(artistId).matches()) {
            throw PublicContentLocale.invalidQuery();
        }
        ArtistHypedBatchRequest batch = ArtistHypedBatchRequest.parse(body);
        UUID festivalId = festivalId(context.snapshot());
        if (batch != null && !batch.festivalId().equals(festivalId)) {
            throw new ApiException(HttpStatus.CONFLICT, "FESTIVAL_MISMATCH", "현재 축제의 요청이 아닙니다.", false);
        }
        Instant now = clock.instant();
        try {
            if (batch != null) {
                var applied = batches.apply(new ArtistHypedBatchService.Command(festivalId,
                    context.snapshot().context().revisionId(), batch.batchId(), artistId, batch.delta(),
                    countPrefix(context.snapshot(), now), now, enabled(context.snapshot(), now)), request);
                updateCachedCount(countKey(context.snapshot(), applied.countPrefix()), artistId, applied.count());
                return incrementResponse(request, context, artistId, applied.count());
            }
            if (!store.isCurrentArtist(context.snapshot().context().revisionId(), artistId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", false);
            }
            if (!enabled(context.snapshot(), now)) {
                throw new ApiException(HttpStatus.CONFLICT, "HYPED_CLOSED", "지금은 기대돼요 참여 시간이 아닙니다.", false);
            }
            String prefix = countPrefix(context.snapshot(), now);
            long count = store.increment(festivalId(context.snapshot()), artistId, prefix, now);
            updateCachedCount(countKey(context.snapshot(), prefix), artistId, count);
            return incrementResponse(request, context, artistId, count);
        } catch (DataAccessException exception) {
            RequestDiagnostics.failure(request, exception);
            throw unavailable();
        }
    }

    private ResponseEntity<ApiResponse<ArtistHypedResponse.Increment>> incrementResponse(
        HttpServletRequest request, Context context, String artistId, long count) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(new ArtistHypedResponse.Increment(artistId, count),
                metaSupport.dynamicMeta(request, context.snapshot().context(), context.locale())));
    }

    // ponytail: one bounded entry for the single-instance deployment; serialize cache misses only here.
    private synchronized List<ArtistHypedResponse.Item> currentCounts(CountKey key) {
        Instant now = clock.instant();
        if (cachedCounts == null || !cachedCounts.key().equals(key)
            || now.isBefore(cachedCounts.loadedAt()) || !now.isBefore(cachedCounts.loadedAt().plusSeconds(1))) {
            var items = store.currentArtists(key.festivalId(), key.revisionId(), key.prefix()).stream()
                .map(count -> new ArtistHypedResponse.Item(count.artistId(), count.hypedCount()))
                .toList();
            cachedCounts = new CachedCounts(key, now, items);
        }
        return cachedCounts.items();
    }

    private synchronized void updateCachedCount(CountKey key, String artistId, long count) {
        if (cachedCounts != null && cachedCounts.key().equals(key)) {
            var items = cachedCounts.items().stream()
                .map(item -> item.artistId().equals(artistId)
                    ? new ArtistHypedResponse.Item(artistId, Math.max(item.hypedCount(), count)) : item)
                .toList();
            cachedCounts = new CachedCounts(key, cachedCounts.loadedAt(), items);
        }
    }

    private CountKey countKey(CatalogSnapshot snapshot, String prefix) {
        return new CountKey(festivalId(snapshot), snapshot.context().revisionId(), prefix);
    }

    private Context context(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshots.required();
        metaSupport.setDynamicContext(request, snapshot.context(), PublicContentLocale.KOREAN);
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
        String locale = PublicContentLocale.requirePublishedLocale(request, snapshots.publishedLocales());
        metaSupport.setDynamicContext(request, snapshot.context(), locale);
        return new Context(snapshot, locale);
    }

    private boolean enabled(CatalogSnapshot snapshot, Instant now) {
        var local = now.atZone(snapshot.context().timezone());
        if (local.toLocalDate().equals(REHEARSAL_DAY)) {
            return !local.toLocalTime().isBefore(REHEARSAL_START);
        }
        return snapshot.home().dates().contains(local.toLocalDate());
    }

    private String countPrefix(CatalogSnapshot snapshot, Instant now) {
        return enabledRehearsalWindow(now, snapshot.context().timezone()) ? "rehearsal-2026-09-28:" : "";
    }

    private boolean enabledRehearsalWindow(Instant now, java.time.ZoneId timezone) {
        var local = now.atZone(timezone);
        LocalTime time = local.toLocalTime();
        return local.toLocalDate().equals(REHEARSAL_DAY)
            && !time.isBefore(REHEARSAL_START) && time.isBefore(REHEARSAL_END);
    }

    private UUID festivalId(CatalogSnapshot snapshot) {
        return UUID.fromString(snapshot.context().festivalId());
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "일시적으로 정보를 불러올 수 없습니다.", true);
    }

    private record Context(CatalogSnapshot snapshot, String locale) {}
    private record CountKey(UUID festivalId, UUID revisionId, String prefix) {}
    private record CachedCounts(CountKey key, Instant loadedAt, List<ArtistHypedResponse.Item> items) {}
}
