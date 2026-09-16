package dev.espero.festival.auth;

import dev.espero.festival.web.ApiErrorResponse;
import dev.espero.festival.web.ApiMeta;
import dev.espero.festival.web.ApiMetaSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiSecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final ApiMetaSupport metaSupport;

    public ApiSecurityErrorWriter(ObjectMapper objectMapper, ApiMetaSupport metaSupport) {
        this.objectMapper = objectMapper;
        this.metaSupport = metaSupport;
    }

    public void write(
        HttpServletRequest request,
        HttpServletResponse response,
        int status,
        String code,
        String message
    ) throws IOException {
        ApiMeta meta = metaSupport.metaForError(request);
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Request-Id", meta.requestId());
        boolean retryable = status == HttpStatus.SERVICE_UNAVAILABLE.value();
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
            new ApiErrorResponse.ErrorBody(code, message, List.of(), retryable), meta
        ));
    }
}
