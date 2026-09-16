package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.StampGuide;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring context): @WebMvcTest's module is not yet a
 * project dependency on this Spring Boot version. See GlobalApiExceptionHandlerTest
 * for the 404/405/500 mapping this controller relies on.
 */
class StampGuideControllerTest {

    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-10-01T09:00:00Z"), ZoneOffset.UTC);
    private final ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
    private final StampGuideController controller = new StampGuideController(snapshots, metaSupport);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void returnsGuideMatchingApiV2Schema() {
        StampGuide guide = new StampGuide(
            "스탬프투어",
            List.of(),
            List.of("안내1", "안내2"),
            "몬스터",
            null,
            null,
            "당일 1회 수령 가능",
            null,
            Instant.parse("2030-10-01T09:00:00Z")
        );
        when(snapshots.required()).thenReturn(snapshot(guide));

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
    }

    @Test
    void throwsServiceUnavailableWhenGuideNotConfigured() {
        when(snapshots.required()).thenReturn(snapshot(null));

        assertThatThrownBy(() -> controller.getStampGuide(request))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).code())
            .isEqualTo("STAMP_GUIDE_NOT_CONFIGURED");
    }

    @Test
    void rejectsUnreadyAndUnknownLocalesWithoutFallingBack() {
        HttpServletRequest knownButUnready = request(Map.of("locale", new String[] {"en"}));
        HttpServletRequest unknown = request(Map.of("locale", new String[] {"xx"}));
        when(snapshots.required()).thenReturn(snapshot(null));
        when(knownButUnready.getParameter("locale")).thenReturn("en");
        when(unknown.getParameter("locale")).thenReturn("xx");

        assertThatThrownBy(() -> controller.getStampGuide(knownButUnready))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("LOCALE_NOT_READY"));
        assertThatThrownBy(() -> controller.getStampGuide(unknown))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("INVALID_QUERY"));
    }

    private CatalogSnapshot snapshot(StampGuide guide) {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                ApiMetaTestFixtures.FESTIVAL_ID.toString(), ApiMetaTestFixtures.REVISION_ID, 7
            ),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            null,
            guide,
            null
        );
    }

    private HttpServletRequest request(Map<String, String[]> parameters) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterMap()).thenReturn(parameters);
        return request;
    }
}
