package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.ArtistHypedStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

    private final CatalogSnapshotProvider snapshots;
    private final ArtistHypedStore store;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;

    public ArtistHypedController(
        CatalogSnapshotProvider snapshots,
        ArtistHypedStore store,
        ApiMetaSupport metaSupport,
        Clock clock
    ) {
        this.snapshots = snapshots;
        this.store = store;
        this.metaSupport = metaSupport;
        this.clock = clock;
    }

    @GetMapping("/artist-hyped")
    public ResponseEntity<ApiResponse<ArtistHypedResponse.Summary>> current(HttpServletRequest request) {
        Context context = context(request);
        Instant now = clock.instant();
        try {
            var items = store.currentArtists(festivalId(context.snapshot()), context.snapshot().context().revisionId())
                .stream()
                .map(count -> new ArtistHypedResponse.Item(count.artistId(), count.hypedCount()))
                .toList();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(
                    new ArtistHypedResponse.Summary(enabled(context.snapshot(), now), items),
                    metaSupport.dynamicMeta(request, context.snapshot().context(), context.locale())
                ));
        } catch (DataAccessException exception) {
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
        if (body == null || !body.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "요청 필드를 확인해 주세요.", false);
        }
        Instant now = clock.instant();
        try {
            if (!store.isCurrentArtist(context.snapshot().context().revisionId(), artistId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", false);
            }
            if (!enabled(context.snapshot(), now)) {
                throw new ApiException(HttpStatus.CONFLICT, "HYPED_CLOSED", "축제일에만 기대돼요에 참여할 수 있습니다.", false);
            }
            long count = store.increment(festivalId(context.snapshot()), artistId, now);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(
                    new ArtistHypedResponse.Increment(artistId, count),
                    metaSupport.dynamicMeta(request, context.snapshot().context(), context.locale())
                ));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
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
        LocalDate today = LocalDate.ofInstant(now, snapshot.context().timezone());
        return snapshot.home().dates().contains(today);
    }

    private UUID festivalId(CatalogSnapshot snapshot) {
        return UUID.fromString(snapshot.context().festivalId());
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "일시적으로 정보를 불러올 수 없습니다.", true);
    }

    private record Context(CatalogSnapshot snapshot, String locale) {}
}
