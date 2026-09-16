package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.context.FestivalContextService;
import dev.espero.festival.domain.StampGuide;
import dev.espero.festival.persistence.StampGuideStore;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring context): @WebMvcTest's module is not yet a
 * project dependency on this Spring Boot version. See GlobalApiExceptionHandlerTest
 * for the 404/405/500 mapping this controller relies on, and
 * PostgreSqlMigrationIntegrationTest in test/backend for the Testcontainers
 * pattern to add if StampGuideStore's array-column mapping needs a real-DB test.
 */
class StampGuideControllerTest {

    private final StampGuideStore store = mock(StampGuideStore.class);
    private final FestivalContextService festivalContextService = mock(FestivalContextService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC);
    private final ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
    private final StampGuideController controller = new StampGuideController(
        store, festivalContextService, metaSupport
    );
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void returnsGuideMatchingApiV2Schema() {
        when(festivalContextService.currentPublished()).thenReturn(ApiMetaTestFixtures.PUBLISHED_CONTEXT);
        when(store.find(ApiMetaTestFixtures.REVISION_ID)).thenReturn(Optional.of(new StampGuide(
            "스탬프투어",
            List.of(),
            List.of("안내1", "안내2"),
            "몬스터",
            null,
            null,
            "당일 1회 수령 가능",
            null,
            Instant.parse("2030-10-01T09:00:00Z")
        )));

        ApiResponse<StampGuideResponse> response = controller.getStampGuide(request);

        assertThat(response.data().title()).isEqualTo("스탬프투어");
        assertThat(response.data().dates()).isEmpty();
        assertThat(response.data().instructions()).containsExactly("안내1", "안내2");
        assertThat(response.data().reward().name()).isEqualTo("몬스터");
        assertThat(response.data().reward().locationText()).isNull();
        assertThat(response.data().dailyLimit()).isEqualTo(4);
        assertThat(response.data().timezone()).isEqualTo("Asia/Seoul");
        assertThat(response.data().qrValue()).isNull();
        assertThat(response.meta().mock()).isFalse();
        assertThat(response.meta().locale()).isEqualTo("ko");
        assertThat(response.meta().festivalId()).isEqualTo(ApiMetaTestFixtures.FESTIVAL_ID.toString());
        assertThat(response.meta().revision()).isEqualTo(7);
        verify(festivalContextService).currentPublished();
        verify(store).find(ApiMetaTestFixtures.REVISION_ID);
    }

    @Test
    void throwsServiceUnavailableWhenGuideNotConfigured() {
        when(festivalContextService.currentPublished()).thenReturn(ApiMetaTestFixtures.PUBLISHED_CONTEXT);
        when(store.find(ApiMetaTestFixtures.REVISION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getStampGuide(request))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).code())
            .isEqualTo("STAMP_GUIDE_NOT_CONFIGURED");
    }
}
