package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates the declared ETag precondition before an administrator mutation starts. */
@Component
public class AdminMutationPreconditions {

    private static final String STRONG_SHA256_ETAG = "^\"[0-9a-f]{64}\"$";

    public void requireCurrentRepresentation(
        HttpServletRequest request,
        AdminMutationConcurrency policy,
        String currentEtag
    ) {
        if (policy == null) {
            throw new IllegalArgumentException("Administrator mutation policy is required");
        }
        if (policy == AdminMutationConcurrency.LAST_WRITE_WINS) {
            return;
        }
        if (request == null || currentEtag == null || !currentEtag.matches(STRONG_SHA256_ETAG)) {
            throw new IllegalArgumentException("A current strong ETag is required for the mutation");
        }

        requireCurrentRepresentation(requireValidIfMatch(request), policy, currentEtag);
    }

    public String requireValidIfMatch(HttpServletRequest request) {
        List<String> values = request == null
            ? List.of()
            : Collections.list(request.getHeaders("If-Match"));
        if (values.isEmpty()) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "PRECONDITION_REQUIRED",
                "최신 상태를 확인한 뒤 다시 저장해 주세요.",
                false
            );
        }
        if (values.size() != 1 || !values.getFirst().trim().matches(STRONG_SHA256_ETAG)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_IF_MATCH",
                "If-Match 헤더 형식이 올바르지 않습니다.",
                false
            );
        }
        return values.getFirst().trim();
    }

    public void requireCurrentRepresentation(
        String validatedIfMatch,
        AdminMutationConcurrency policy,
        String currentEtag
    ) {
        if (policy == null) {
            throw new IllegalArgumentException("Administrator mutation policy is required");
        }
        if (policy == AdminMutationConcurrency.LAST_WRITE_WINS) {
            return;
        }
        if (validatedIfMatch == null || !validatedIfMatch.matches(STRONG_SHA256_ETAG)
            || currentEtag == null || !currentEtag.matches(STRONG_SHA256_ETAG)) {
            throw new IllegalArgumentException("Current and provided strong ETags are required for the mutation");
        }
        if (!currentEtag.equals(validatedIfMatch)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "EDIT_CONFLICT",
                "다른 관리자가 먼저 변경했습니다. 최신 상태를 확인해 주세요.",
                false
            );
        }
    }
}
