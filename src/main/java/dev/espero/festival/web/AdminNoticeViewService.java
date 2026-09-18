package dev.espero.festival.web;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.Notice;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.espero.festival.persistence.NoticeStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Builds the administrator notice representation: full multi-locale translations and links. */
@Service
@Profile("db")
public class AdminNoticeViewService {

    private static final ZoneId TIMEZONE = ZoneId.of("Asia/Seoul");
    private static final List<String> LABEL_LOCALES = List.of("ko", "en", "zh-Hans", "ja");

    private final NoticeStore store;
    private final FestivalProperties properties;
    private final ApiMetaSupport metaSupport;
    private final ConditionalResponseSupport conditionalResponses;

    public AdminNoticeViewService(
        NoticeStore store,
        FestivalProperties properties,
        ApiMetaSupport metaSupport,
        ConditionalResponseSupport conditionalResponses
    ) {
        this.store = store;
        this.properties = properties;
        this.metaSupport = metaSupport;
        this.conditionalResponses = conditionalResponses;
    }

    public AdminNoticesSnapshot list(HttpServletRequest request) {
        UUID festivalId = properties.configuredFestivalId();
        List<Notice> notices = store.findAllForAdmin(festivalId);
        AdminNoticesResponse response = new AdminNoticesResponse(notices.stream().map(AdminNoticeViewService::toResponse).toList());
        ApiMeta meta = metaSupport.unscopedMeta(request, "ko");
        return new AdminNoticesSnapshot(response, meta);
    }

    public AdminNoticeSnapshot find(HttpServletRequest request, UUID noticeId) {
        UUID festivalId = properties.configuredFestivalId();
        Notice notice = store.findForAdmin(festivalId, noticeId).orElseThrow(AdminNoticeViewService::notFound);
        return snapshot(request, notice);
    }

    public AdminNoticeSnapshot snapshot(HttpServletRequest request, Notice notice) {
        AdminNoticeResponse response = toResponse(notice);
        ApiMeta meta = metaSupport.unscopedMeta(request, "ko");
        String etag = conditionalResponses.strongEtag(
            new ConditionalApiResponse<>(response, ConditionalApiMeta.from(meta))
        );
        return new AdminNoticeSnapshot(notice, response, meta, etag);
    }

    static AdminNoticeResponse toResponse(Notice notice) {
        Map<String, AdminNoticeTranslationResponse> translations = new LinkedHashMap<>();
        notice.translations().forEach((locale, translation) ->
            translations.put(locale, new AdminNoticeTranslationResponse(translation.title(), translation.body())));
        List<AdminNoticeLinkResponse> links = notice.links().stream()
            .map(link -> new AdminNoticeLinkResponse(link.url(), fullLabels(link.labels())))
            .toList();
        return new AdminNoticeResponse(
            notice.id().toString(),
            notice.category().name(),
            translations,
            links,
            null,
            OffsetDateTime.ofInstant(notice.createdAt(), TIMEZONE),
            OffsetDateTime.ofInstant(notice.updatedAt(), TIMEZONE)
        );
    }

    private static Map<String, String> fullLabels(Map<String, String> sparse) {
        Map<String, String> full = new LinkedHashMap<>();
        for (String locale : LABEL_LOCALES) {
            full.put(locale, sparse.get(locale));
        }
        return full;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 정보를 찾을 수 없습니다.", false);
    }

    public record AdminNoticesSnapshot(AdminNoticesResponse response, ApiMeta meta) {}

    public record AdminNoticeSnapshot(Notice notice, AdminNoticeResponse response, ApiMeta meta, String etag) {}
}
