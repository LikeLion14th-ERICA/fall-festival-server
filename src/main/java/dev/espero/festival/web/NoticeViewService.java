package dev.espero.festival.web;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.Notice;
import dev.espero.festival.domain.NoticeTranslation;
import dev.espero.festival.persistence.NoticeStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Builds the public notice list from the current KST day's visibility window. */
@Service
@Profile("db")
public class NoticeViewService {

    private static final ZoneId TIMEZONE = ZoneId.of("Asia/Seoul");

    private final NoticeStore store;
    private final FestivalProperties properties;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;

    public NoticeViewService(
        NoticeStore store,
        FestivalProperties properties,
        ApiMetaSupport metaSupport,
        Clock clock
    ) {
        this.store = store;
        this.properties = properties;
        this.metaSupport = metaSupport;
        this.clock = clock;
    }

    public NoticeListSnapshot list(HttpServletRequest request) {
        String requestedLocale = NoticeContentLocale.requestedLocale(request);
        UUID festivalId = properties.configuredFestivalId();
        LocalDate today = LocalDate.now(clock.withZone(TIMEZONE));
        ZonedDateTime windowStart = today.atStartOfDay(TIMEZONE);
        ZonedDateTime windowEnd = windowStart.plusDays(1);
        List<Notice> notices = store.findVisible(festivalId, windowStart.toInstant(), windowEnd.toInstant());
        List<NoticeResponse> items = notices.stream()
            .map(notice -> toResponse(notice, requestedLocale))
            .toList();
        List<String> visibleIds = items.stream().map(NoticeResponse::id).toList();
        NoticesResponse response = new NoticesResponse(items, visibleIds, today);
        ApiMeta meta = metaSupport.unscopedMeta(request, requestedLocale);
        return new NoticeListSnapshot(response, meta);
    }

    private NoticeResponse toResponse(Notice notice, String requestedLocale) {
        String contentLocale = NoticeContentLocale.resolve(notice.translations(), requestedLocale);
        NoticeTranslation translation = notice.translations().get(contentLocale);
        List<NoticeLinkResponse> links = notice.links().stream()
            .map(link -> new NoticeLinkResponse(link.url(), link.labels().get(contentLocale), "_blank"))
            .toList();
        return new NoticeResponse(
            notice.id().toString(),
            notice.category().name(),
            contentLocale,
            translation.title(),
            translation.body(),
            links,
            OffsetDateTime.ofInstant(notice.createdAt(), TIMEZONE)
        );
    }

    public record NoticeListSnapshot(NoticesResponse response, ApiMeta meta) {}
}
