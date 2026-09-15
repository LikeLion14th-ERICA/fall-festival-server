package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.domain.CatalogSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiMetaSupportTest {

    private final ApiMetaSupport metaSupport = new ApiMetaSupport(
        Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void retainsAValidClientRequestIdInMeta() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/maps");
        request.addHeader("X-Request-Id", "catalog-42.trace");

        ApiMeta meta = metaSupport.meta(request, new CatalogSnapshot.FestivalContext(
            "festival-test", UUID.fromString("00000000-0000-0000-0000-000000000001"), 2
        ), "ko");

        assertThat(meta.requestId()).isEqualTo("catalog-42.trace");
        assertThat(meta.festivalId()).isEqualTo("festival-test");
        assertThat(meta.revision()).isEqualTo(2);
    }

    @Test
    void truncatesServerTimeToMillisecondPrecision() {
        ApiMetaSupport microsecondClock = new ApiMetaSupport(
            Clock.fixed(Instant.parse("2030-10-01T09:00:00.123456789Z"), ZoneOffset.UTC)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/maps");

        ApiMeta meta = microsecondClock.meta(request, 3, "ko");

        assertThat(meta.serverTime().getNano()).isEqualTo(123_000_000);
    }

    @Test
    void replacesInvalidClientRequestIdAndWritesTheSameHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/maps");
        request.addHeader("X-Request-Id", "not allowed because it has spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, new MockFilterChain());

        String requestId = response.getHeader("X-Request-Id");
        assertThat(requestId).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
        assertThat(metaSupport.metaForError(request).requestId()).isEqualTo(requestId);
        assertThat(metaSupport.metaForError(request).revision()).isEqualTo(1);
    }
}
