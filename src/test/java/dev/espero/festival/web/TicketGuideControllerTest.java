package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.TicketGuideConfig;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

class TicketGuideControllerTest {

    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC);

    private TicketGuideController controllerWithPrice(Integer amount) {
        when(request.getParameterMap()).thenReturn(Map.of());
        TicketGuideConfig guide = new TicketGuideConfig(amount, List.of(), null, null, null, null, null, null,
            Instant.parse("2030-09-01T00:00:00Z"));
        when(snapshots.required()).thenReturn(new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                ApiMetaTestFixtures.FESTIVAL_ID.toString(), ApiMetaTestFixtures.REVISION_ID, 7
            ),
            List.of(), List.of(), List.of(), Map.of(), guide, null
        ));
        return new TicketGuideController(
            snapshots, new ConditionalResponseSupport(new ObjectMapper()),
            ApiMetaTestFixtures.contentMetaSupport(clock)
        );
    }

    @Test
    void servesOnlyThePublishedAmountWithoutACompleteSchedule() {
        TicketGuideController controller = controllerWithPrice(25000);
        ResponseEntity<ConditionalApiResponse<TicketGuideResponse>> response = controller.getTicketGuide(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().unitPrice()).isEqualTo(new TicketGuideResponse.Money(25000, "KRW"));
        assertThat(response.getBody().meta().revision()).isEqualTo(7);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-cache");
        String etag = response.getHeaders().getFirst(ConditionalResponseSupport.ETAG_HEADER);
        assertThat(etag).matches("\"[0-9a-f]{64}\"");

        HttpServletRequest revalidation = mock(HttpServletRequest.class);
        when(revalidation.getParameterMap()).thenReturn(Map.of());
        when(revalidation.getHeader(ConditionalResponseSupport.IF_NONE_MATCH_HEADER)).thenReturn(etag);
        ResponseEntity<ConditionalApiResponse<TicketGuideResponse>> notModified = controller.getTicketGuide(revalidation);
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNull();
    }

    @Test
    void preservesNullForAnOlderCatalogWithoutAConfirmedAmount() {
        TicketGuideResponse data = controllerWithPrice(null).getTicketGuide(request).getBody().data();
        assertThat(data.unitPrice()).isNull();
    }

    @Test
    void rejectsAnUnreadyOrUnknownLocale() {
        TicketGuideController controller = controllerWithPrice(25000);
        HttpServletRequest unready = mock(HttpServletRequest.class);
        when(unready.getParameterMap()).thenReturn(Map.of("locale", new String[] {"en"}));
        when(unready.getParameter("locale")).thenReturn("en");
        assertThatThrownBy(() -> controller.getTicketGuide(unready))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("LOCALE_NOT_READY"));
    }
}
