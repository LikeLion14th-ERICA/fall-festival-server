package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.CrowdingRecord;
import dev.espero.festival.persistence.CrowdingStore;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CrowdingControllerTest {

    private final CrowdingStore store = mock(CrowdingStore.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private CrowdingController controllerAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new CrowdingController(store, ApiMetaTestFixtures.contentMetaSupport(clock), clock);
    }

    @Test
    void returnsBeforeOpenAheadOfOpeningTime() {
        when(store.findFor(LocalDate.parse("2030-10-01"))).thenReturn(Optional.empty());

        CrowdingResponse data = controllerAt("2030-10-01T02:00:00Z").getCrowding(request).data();

        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.BEFORE_OPEN);
        assertThat(data.colorToken()).isNull();
        assertThat(data.savedLevel()).isNull();
        assertThat(data.timeBasis()).isEqualTo(CrowdingResponse.TimeBasis.NONE);
        assertThat(data.message()).contains("13:00");
    }

    @Test
    void returnsRelaxedDefaultDuringOperatingHoursWhenNothingSaved() {
        when(store.findFor(LocalDate.parse("2030-10-01"))).thenReturn(Optional.empty());

        ApiResponse<CrowdingResponse> response = controllerAt("2030-10-01T05:00:00Z").getCrowding(request);
        CrowdingResponse data = response.data();

        assertThat(data.status()).isEqualTo(CrowdingResponse.Status.RELAXED);
        assertThat(data.colorToken()).isEqualTo("green");
        assertThat(data.savedLevel()).isNull();
        assertThat(data.timeBasis()).isEqualTo(CrowdingResponse.TimeBasis.OPENING);
        assertThat(response.meta().festivalId()).isEqualTo(ApiMetaTestFixtures.FESTIVAL_ID.toString());
        assertThat(response.meta().revision()).isZero();
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
    }
}
