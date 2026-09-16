package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.persistence.CrowdingStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CrowdingControllerTest {

    private final CrowdingStore store = mock(CrowdingStore.class);
    private final HttpServletRequest request = request(Map.of());

    private CrowdingController controllerAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new CrowdingController(store, new ApiMetaSupport(clock), clock);
    }

    @Test
    void returnsBeforeOpenAheadOfOpeningTime() {
        when(store.findFor(LocalDate.parse("2030-10-01"))).thenReturn(Optional.empty());

        CrowdingResponse data = controllerAt("2030-10-01T02:00:00Z").getCrowding(request).data();

        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.BEFORE_OPEN);
        assertThat(data.colorToken()).isNull();
        assertThat(data.savedLevel()).isNull();
        assertThat(data.timeBasis()).isEqualTo(CrowdingResponse.TimeBasis.NONE);
        assertThat(data.updatedAt()).isNull();
        assertThat(data.message()).contains("13:00");
    }

    @Test
    void returnsRelaxedDefaultDuringOperatingHoursWhenNothingSaved() {
        when(store.findFor(LocalDate.parse("2030-10-01"))).thenReturn(Optional.empty());

        CrowdingResponse data = controllerAt("2030-10-01T05:00:00Z").getCrowding(request).data();

        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.RELAXED);
        assertThat(data.colorToken()).isEqualTo("green");
        assertThat(data.savedLevel()).isNull();
        assertThat(data.timeBasis()).isEqualTo(CrowdingResponse.TimeBasis.OPENING);
    }

    @Test
    void returnsSavedLevelDuringOperatingHours() {
        when(store.findFor(LocalDate.parse("2030-10-01")))
            .thenReturn(Optional.of(new CrowdingRecord("FULL", Instant.parse("2030-10-01T06:30:00Z"))));

        CrowdingResponse data = controllerAt("2030-10-01T07:00:00Z").getCrowding(request).data();

        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.FULL);
        assertThat(data.colorToken()).isEqualTo("black");
        assertThat(data.savedLevel()).isEqualTo("FULL");
        assertThat(data.timeBasis()).isEqualTo(CrowdingResponse.TimeBasis.OPERATOR);
        assertThat(data.updatedAt()).isNotNull();
    }

    @Test
    void returnsClosedAtOrAfterClosingTimeAndIgnoresSavedLevel() {
        when(store.findFor(LocalDate.parse("2030-10-01")))
            .thenReturn(Optional.of(new CrowdingRecord("CROWDED", Instant.parse("2030-10-01T10:00:00Z"))));

        CrowdingResponse data = controllerAt("2030-10-01T13:00:00Z").getCrowding(request).data();

        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.CLOSED);
        assertThat(data.colorToken()).isNull();
        assertThat(data.timeBasis()).isEqualTo(CrowdingResponse.TimeBasis.NONE);
        assertThat(data.updatedAt()).isNull();
    }

    @Test
    void distinguishesUnreadyKnownLocaleFromInvalidLocaleAndQuery() {
        when(store.findFor(LocalDate.parse("2030-10-01"))).thenReturn(Optional.empty());
        HttpServletRequest knownButUnready = request(Map.of("locale", new String[] {"en"}));
        HttpServletRequest unknown = request(Map.of("locale", new String[] {"xx"}));
        HttpServletRequest duplicate = request(Map.of("locale", new String[] {"ko", "ko"}));
        when(knownButUnready.getParameter("locale")).thenReturn("en");
        when(unknown.getParameter("locale")).thenReturn("xx");

        assertThatThrownBy(() -> controllerAt("2030-10-01T05:00:00Z").getCrowding(knownButUnready))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("LOCALE_NOT_READY"));
        assertThatThrownBy(() -> controllerAt("2030-10-01T05:00:00Z").getCrowding(unknown))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("INVALID_QUERY"));
        assertThatThrownBy(() -> controllerAt("2030-10-01T05:00:00Z").getCrowding(duplicate))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("INVALID_QUERY"));
    }

    private HttpServletRequest request(Map<String, String[]> parameters) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterMap()).thenReturn(parameters);
        return request;
    }
}
