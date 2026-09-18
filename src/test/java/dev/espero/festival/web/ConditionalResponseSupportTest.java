package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class ConditionalResponseSupportTest {

    private final ConditionalResponseSupport support = new ConditionalResponseSupport(new ObjectMapper());
    private final ApiMeta meta = new ApiMeta(
        "meta-request-id",
        OffsetDateTime.parse("2030-10-01T12:00:00+09:00"),
        "Asia/Seoul",
        "festival-1",
        7,
        "ko",
        false
    );

    @Test
    void returnsAStableStrongEtagAndMovesVolatileMetadataToHeaders() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Request-Id", "client-controlled");

        ResponseEntity<ConditionalApiResponse<SampleData>> response = support.respond(
            request,
            dataInOrder("b", "a"),
            meta
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).matches("\"[0-9a-f]{64}\"");
        assertThat(response.getHeaders().getFirst(ConditionalResponseSupport.REQUEST_ID_HEADER))
            .matches("[0-9a-f-]{36}")
            .isNotEqualTo("client-controlled");
        assertThat(response.getHeaders().getFirst(ConditionalResponseSupport.SERVER_TIME_HEADER))
            .isEqualTo(meta.serverTime().toString());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta()).isEqualTo(ConditionalApiMeta.from(meta));
    }

    @Test
    void returns304ForMatchingStrongOrWeakIfNoneMatchWithoutABody() {
        ResponseEntity<ConditionalApiResponse<SampleData>> first = support.respond(
            request(), dataInOrder("a", "b"), meta
        );
        String etag = first.getHeaders().getETag();

        MockHttpServletRequest secondRequest = request();
        secondRequest.addHeader("If-None-Match", "W/" + etag);
        ResponseEntity<ConditionalApiResponse<SampleData>> second = support.respond(
            secondRequest, dataInOrder("b", "a"), meta
        );

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(second.getBody()).isNull();
        assertThat(second.getHeaders().getETag()).isEqualTo(etag);
    }

    @Test
    void canonicalizesMapOrderAndExcludesRequestSpecificMetadataFromTheEtag() {
        String first = support.respond(
            request(), dataInOrder("a", "b"), meta
        ).getHeaders().getETag();
        ApiMeta differentRequest = new ApiMeta(
            "another-request-id",
            OffsetDateTime.parse("2030-10-01T12:00:01+09:00"),
            meta.timezone(), meta.festivalId(), meta.revision(), meta.locale(), meta.mock()
        );
        String second = support.respond(
            request(), dataInOrder("b", "a"), differentRequest
        ).getHeaders().getETag();

        assertThat(second).isEqualTo(first);
        assertThat(support.respond(request(), new SampleData("changed"), meta)
            .getHeaders().getETag()).isNotEqualTo(first);
    }

    @Test
    void supportsWildcardIfNoneMatch() {
        MockHttpServletRequest request = request();
        request.addHeader("If-None-Match", "*");

        ResponseEntity<ConditionalApiResponse<SampleData>> response = support.respond(
            request, new SampleData("unchanged"), meta
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v2/conditional");
    }

    private SampleData dataInOrder(String first, String second) {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(first, valueFor(first));
        values.put(second, valueFor(second));
        return new SampleData(values);
    }

    private int valueFor(String key) {
        return key.equals("a") ? 1 : 2;
    }

    private record SampleData(Object value) {}
}
