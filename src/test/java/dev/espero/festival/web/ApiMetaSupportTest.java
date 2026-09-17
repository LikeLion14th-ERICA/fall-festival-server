package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.PublishedFestivalContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiMetaSupportTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");
    private final Clock clock = Clock.fixed(Instant.parse("2030-10-01T03:00:00Z"), ZoneOffset.UTC);
    private final FestivalProperties properties = new FestivalProperties(FESTIVAL_ID.toString());
    private final ApiMetaSupport metaSupport = new ApiMetaSupport(clock, properties);

    @Test
    void ignoresAClientRequestIdAndUsesAServerGeneratedValue() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/maps");
        request.addHeader("X-Request-Id", "catalog-42.trace");

        ApiMeta meta = metaSupport.meta(request, new CatalogSnapshot.FestivalContext(
            FESTIVAL_ID.toString(), REVISION_ID, 2
        ), "ko");

        assertThat(meta.requestId())
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
            .isNotEqualTo("catalog-42.trace");
        assertThat(meta.festivalId()).isEqualTo(FESTIVAL_ID.toString());
        assertThat(meta.revision()).isEqualTo(2);
    }

    @Test
    void truncatesServerTimeToMillisecondPrecision() {
        ApiMetaSupport microsecondClock = new ApiMetaSupport(
            Clock.fixed(Instant.parse("2030-10-01T09:00:00.123456789Z"), ZoneOffset.UTC),
            properties
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/maps");

        ApiMeta meta = microsecondClock.meta(request, new CatalogSnapshot.FestivalContext(
            FESTIVAL_ID.toString(), REVISION_ID, 3
        ), "ko");

        assertThat(meta.serverTime().getNano()).isEqualTo(123_000_000);
    }

    @Test
    void ignoresInvalidClientRequestIdAndWritesTheSameServerHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/maps");
        request.addHeader("X-Request-Id", "not allowed because it has spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, new MockFilterChain());

        String requestId = response.getHeader("X-Request-Id");
        assertThat(requestId)
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
            .isNotEqualTo("not allowed because it has spaces");
        assertThat(metaSupport.metaForError(request).requestId()).isEqualTo(requestId);
        assertThat(metaSupport.metaForError(request).festivalId()).isEqualTo(FESTIVAL_ID.toString());
        assertThat(metaSupport.metaForError(request).revision()).isZero();
    }

    @Test
    void contentMetaUsesPublishedContextAndErrorMetaPreservesIt() {
        PublishedFestivalContext context = new PublishedFestivalContext(
            FESTIVAL_ID, REVISION_ID, 9, ZoneId.of("Asia/Seoul")
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "festival-context-request");

        ApiMeta meta = metaSupport.contentMeta(request, "en", context);
        ApiMeta errorMeta = metaSupport.metaForError(request);

        assertThat(meta.festivalId()).isEqualTo(FESTIVAL_ID.toString());
        assertThat(meta.revision()).isEqualTo(9);
        assertThat(meta.timezone()).isEqualTo("Asia/Seoul");
        assertThat(meta.requestId()).matches("[0-9a-f-]{36}").isNotEqualTo("festival-context-request");
        assertThat(meta.locale()).isEqualTo("en");
        assertThat(errorMeta).isEqualTo(meta);
    }

    @Test
    void unscopedMetaUsesConfiguredFestivalAndRevisionZero() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ApiMeta meta = metaSupport.unscopedMeta(request, "ko");

        assertThat(meta.festivalId()).isEqualTo(FESTIVAL_ID.toString());
        assertThat(meta.revision()).isZero();
        assertThat(meta.timezone()).isEqualTo("Asia/Seoul");
    }
}
