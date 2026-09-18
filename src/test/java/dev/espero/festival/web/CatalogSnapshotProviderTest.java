package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.CatalogSnapshotStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(OutputCaptureExtension.class)
class CatalogSnapshotProviderTest {

    private final CatalogSnapshotStore store = mock(CatalogSnapshotStore.class);

    @Test
    void makesThePublishedSnapshotAvailableAfterStartupLoad(CapturedOutput output) {
        CatalogSnapshot snapshot = emptySnapshot();
        when(store.loadPublished()).thenReturn(snapshot);
        CatalogSnapshotProvider provider = new CatalogSnapshotProvider(store);

        provider.run(new DefaultApplicationArguments());

        assertThat(provider.isReady()).isTrue();
        assertThat(provider.required()).isSameAs(snapshot);
        assertThat(provider.unavailableReason()).isNull();
        assertThat(output)
            .contains("Loaded published catalog snapshot")
            .contains("revision=3")
            .doesNotContain("00000000-0000-0000-0000-000000000003")
            .doesNotContain("festivalId=");
    }

    @Test
    void failedStartupLoadLeavesTheCatalogUnavailable() {
        when(store.loadPublished()).thenThrow(new IllegalStateException("invalid published catalog"));
        CatalogSnapshotProvider provider = new CatalogSnapshotProvider(store);

        provider.run(new DefaultApplicationArguments());

        assertThat(provider.isReady()).isFalse();
        assertThat(provider.unavailableReason()).isEqualTo("Published catalog could not be loaded.");
        assertThatThrownBy(provider::required)
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException apiException = (ApiException) exception;
                assertThat(apiException.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(apiException.code()).isEqualTo("CATALOG_NOT_READY");
            });
    }

    private CatalogSnapshot emptySnapshot() {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                "festival-catalog",
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                3
            ),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            null
        );
    }
}
