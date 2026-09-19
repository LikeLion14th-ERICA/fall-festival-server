package dev.espero.festival.web;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.auth.AdminContext;
import dev.espero.festival.auth.AdminPrincipal;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.idempotency.AdminIdempotencyService;
import dev.espero.festival.idempotency.CanonicalPayload;
import dev.espero.festival.idempotency.IdempotencyExecution;
import dev.espero.festival.idempotency.IdempotencyKeyPolicy;
import dev.espero.festival.idempotency.IdempotencyRequest;
import dev.espero.festival.idempotency.IdempotencyResponse;
import dev.espero.festival.media.GoodsImageInspection;
import dev.espero.festival.media.GoodsImageInspector;
import dev.espero.festival.media.GoodsImageProcessor;
import dev.espero.festival.media.GoodsImageUploadSpooler;
import dev.espero.festival.media.GoodsImageUploadTooLargeException;
import dev.espero.festival.media.GoodsImageValidationException;
import dev.espero.festival.media.MediaProcessingBusyException;
import dev.espero.festival.media.MediaServiceUnavailableException;
import dev.espero.festival.media.MediaStorage;
import dev.espero.festival.media.ProcessedGoodsImage;
import dev.espero.festival.persistence.MediaAssetStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/** Authenticated upload orchestration for unattached goods images. */
@RestController
@RequestMapping("/api/v2/admin/media")
@Profile("db")
@ConditionalOnProperty(prefix = "festival.media", name = "storage-root")
public class AdminGoodsImageController {

    static final String UPLOAD_ROUTE = "/api/v2/admin/media/goods-images";

    private final GoodsImageUploadSpooler spooler;
    private final GoodsImageInspector inspector;
    private final GoodsImageProcessor processor;
    private final MediaStorage mediaStorage;
    private final MediaAssetStore mediaAssets;
    private final FestivalProperties festivalProperties;
    private final AdminIdempotencyService idempotency;
    private final AdminAuditService audit;
    private final AdminContext adminContext;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AdminGoodsImageController(
        GoodsImageUploadSpooler spooler,
        GoodsImageInspector inspector,
        GoodsImageProcessor processor,
        MediaStorage mediaStorage,
        MediaAssetStore mediaAssets,
        FestivalProperties festivalProperties,
        AdminIdempotencyService idempotency,
        AdminAuditService audit,
        AdminContext adminContext,
        ApiMetaSupport metaSupport,
        Clock clock,
        ObjectMapper objectMapper
    ) {
        this.spooler = spooler;
        this.inspector = inspector;
        this.processor = processor;
        this.mediaStorage = mediaStorage;
        this.mediaAssets = mediaAssets;
        this.festivalProperties = festivalProperties;
        this.idempotency = idempotency;
        this.audit = audit;
        this.adminContext = adminContext;
        this.metaSupport = metaSupport;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/goods-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AdminGoodsImageUploadResponse>> upload(
        MultipartHttpServletRequest request
    ) throws GoodsImageValidationException, GoodsImageUploadTooLargeException, MediaProcessingBusyException {
        validateQuery(request);
        MultipartFile file = requireSingleFilePart(request);
        String idempotencyKey = requireIdempotencyKey(request);
        AdminPrincipal principal = adminContext.requireCurrent();
        UUID festivalId = festivalProperties.configuredFestivalId();

        try (GoodsImageUploadSpooler.SpoolFile source = spooler.spool(file.getInputStream())) {
            GoodsImageInspection inspection = inspector.inspect(source.path());
            UUID operationId = UUID.randomUUID();
            UUID proposedMediaId = UUID.randomUUID();
            return processUpload(
                request,
                source,
                inspection,
                operationId,
                proposedMediaId,
                festivalId,
                principal,
                idempotencyKey
            );
        } catch (GoodsImageValidationException | GoodsImageUploadTooLargeException exception) {
            throw exception;
        } catch (MediaProcessingBusyException exception) {
            throw exception;
        } catch (IOException exception) {
            throw unavailable("Goods image upload infrastructure failed", exception);
        }
    }

    private ResponseEntity<ApiResponse<AdminGoodsImageUploadResponse>> processUpload(
        HttpServletRequest request,
        GoodsImageUploadSpooler.SpoolFile source,
        GoodsImageInspection inspection,
        UUID operationId,
        UUID proposedMediaId,
        UUID festivalId,
        AdminPrincipal principal,
        String idempotencyKey
    ) throws IOException, MediaProcessingBusyException {
        Throwable failure = null;
        try {
            ProcessedGoodsImage processed = processor.process(source.path(), inspection, operationId);
            IdempotencyRequest idempotencyRequest = new IdempotencyRequest(
                principal.adminId(),
                "POST",
                UPLOAD_ROUTE,
                festivalId + "/GOODS_IMAGE",
                idempotencyKey,
                CanonicalPayload.from(Map.of(
                    "purpose", "GOODS_IMAGE",
                    "binarySha256", inspection.sourceSha256(),
                    "byteLength", inspection.sourceSizeBytes(),
                    "detectedFormat", inspection.format().name()
                ))
            );
            AtomicBoolean finalizationAttempted = new AtomicBoolean();

            IdempotencyExecution execution;
            try {
                execution = idempotency.execute(idempotencyRequest, () -> {
                    finalizationAttempted.set(true);
                    try {
                        mediaStorage.finalizeStaging(operationId, festivalId, proposedMediaId);
                    } catch (IOException exception) {
                        throw unavailable("Goods image storage finalization failed", exception);
                    }
                    mediaAssets.insert(
                        proposedMediaId,
                        festivalId,
                        mediaStorage.storageKey(festivalId, proposedMediaId),
                        processed,
                        clock.instant()
                    );
                    audit.record(
                        AdminAuditAction.GOODS_IMAGE_UPLOADED,
                        AdminAuditResourceType.MEDIA,
                        proposedMediaId.toString(),
                        ApiMetaSupport.resolveRequestId(request)
                    );
                    return storedResponse(request, proposedMediaId);
                });
            } catch (RuntimeException exception) {
                if (finalizationAttempted.get()) {
                    cleanupFinalMedia(exception, festivalId, proposedMediaId);
                }
                throw exception;
            }

            UUID mediaId = storedMediaId(execution.response());
            ApiResponse<AdminGoodsImageUploadResponse> response = new ApiResponse<>(
                new AdminGoodsImageUploadResponse(mediaId.toString()),
                metaSupport.unscopedMeta(request, "ko")
            );
            return ResponseEntity.status(execution.response().status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
        } catch (IOException | RuntimeException | MediaProcessingBusyException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                mediaStorage.discardStaging(operationId);
            } catch (IOException | RuntimeException cleanupFailure) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw unavailable("Goods image staging cleanup failed", cleanupFailure);
                }
            }
        }
    }

    private IdempotencyResponse storedResponse(HttpServletRequest request, UUID mediaId) {
        String body = objectMapper.writeValueAsString(new ApiResponse<>(
            new AdminGoodsImageUploadResponse(mediaId.toString()),
            metaSupport.unscopedMeta(request, "ko")
        ));
        return new IdempotencyResponse(HttpStatus.CREATED.value(), MediaType.APPLICATION_JSON_VALUE, body);
    }

    private UUID storedMediaId(IdempotencyResponse response) {
        try {
            String value = objectMapper.readTree(response.body()).path("data").path("mediaId").asString();
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw unavailable("Stored goods image upload response is invalid", exception);
        }
    }

    private void cleanupFinalMedia(RuntimeException original, UUID festivalId, UUID mediaId) {
        try {
            if (!mediaAssets.exists(festivalId, mediaId)) {
                mediaStorage.delete(festivalId, mediaId);
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private MultipartFile requireSingleFilePart(MultipartHttpServletRequest request) {
        int fileCount = request.getMultiFileMap().values().stream().mapToInt(List::size).sum();
        List<MultipartFile> files = request.getMultiFileMap().get("file");
        if (!request.getParameterMap().isEmpty()
            || request.getMultiFileMap().size() != 1
            || fileCount != 1
            || files == null
            || files.size() != 1
            || files.getFirst().isEmpty()) {
            throw validation();
        }
        return files.getFirst();
    }

    private String requireIdempotencyKey(HttpServletRequest request) {
        List<String> keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.isEmpty()) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key 헤더가 필요합니다.",
                false
            );
        }
        if (keys.size() != 1 || !IdempotencyKeyPolicy.isValid(keys.getFirst())) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key 헤더 형식이 올바르지 않습니다.",
                false
            );
        }
        return keys.getFirst();
    }

    private void validateQuery(HttpServletRequest request) {
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "요청 파라미터를 확인해 주세요.", false);
        }
    }

    private static ApiException validation() {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "VALIDATION_FAILED",
            "요청 파일을 확인해 주세요.",
            false
        );
    }

    private static MediaServiceUnavailableException unavailable(String message, Throwable cause) {
        return new MediaServiceUnavailableException(message, cause);
    }
}
