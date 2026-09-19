package dev.espero.festival.web;

import dev.espero.festival.domain.NoticeTemplate;
import dev.espero.festival.domain.NoticeTranslation;
import dev.espero.festival.persistence.NoticeTemplateStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only notice templates for the administrator notice form
 * (ADM-NOTICE-004). Templates are replaced with the notice-template CLI.
 */
@RestController
@RequestMapping("/api/v2/admin")
@Profile("db")
public class NoticeTemplateController {

    private static final List<String> LOCALES = List.of("ko", "en", "zh-Hans", "ja");

    private final NoticeTemplateStore store;
    private final ApiMetaSupport metaSupport;

    public NoticeTemplateController(NoticeTemplateStore store, ApiMetaSupport metaSupport) {
        this.store = store;
        this.metaSupport = metaSupport;
    }

    @GetMapping("/notice-templates")
    public ApiResponse<Templates> getTemplates(HttpServletRequest request) {
        validateQuery(request);
        List<Template> items = store.findAll().stream().map(NoticeTemplateController::response).toList();
        return new ApiResponse<>(new Templates(items), metaSupport.unscopedMeta(request, "ko"));
    }

    @GetMapping("/notice-templates/{templateId}")
    public ApiResponse<Template> getTemplate(HttpServletRequest request, @PathVariable String templateId) {
        validateQuery(request);
        NoticeTemplate template = store.find(templateId).orElseThrow(() -> new ApiException(
            HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 정보를 찾을 수 없습니다.", false
        ));
        return new ApiResponse<>(response(template), metaSupport.unscopedMeta(request, "ko"));
    }

    private static Template response(NoticeTemplate template) {
        Map<String, AdminNoticeTranslationResponse> translations = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            NoticeTranslation translation = template.translations().get(locale);
            if (translation != null) {
                translations.put(locale, new AdminNoticeTranslationResponse(translation.title(), translation.body()));
            }
        }
        return new Template(template.id(), template.name(), translations);
    }

    private static void validateQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
        }
    }

    public record Template(String id, String name, Map<String, AdminNoticeTranslationResponse> translations) {}

    public record Templates(List<Template> items) {}
}
