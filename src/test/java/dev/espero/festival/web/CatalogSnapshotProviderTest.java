package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.CatalogSnapshotStore;
import dev.espero.festival.persistence.LocaleCompletenessStore;
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
    private final LocaleCompletenessStore completeness = mock(LocaleCompletenessStore.class);

    @Test
    void makesThePublishedSnapshotAvailableAfterStartupLoad(CapturedOutput output) {
        CatalogSnapshot snapshot = emptySnapshot();
        when(store.loadPublished()).thenReturn(snapshot);
        CatalogSnapshotProvider provider = new CatalogSnapshotProvider(store, completeness, "ko");

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
        CatalogSnapshotProvider provider = new CatalogSnapshotProvider(store, completeness, "ko");

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

    @Test
    void publishesACompleteLocaleAndLeavesAnIncompleteOneOut(CapturedOutput output) {
        CatalogSnapshot korean = emptySnapshot();
        CatalogSnapshot english = emptySnapshot();
        UUID revision = korean.context().revisionId();
        when(store.loadPublished()).thenReturn(korean);
        when(store.loadRevision(revision, "en")).thenReturn(english);
        when(completeness.findings(revision, "en")).thenReturn(List.of());
        when(completeness.findings(revision, "zh-Hans"))
            .thenReturn(List.of("space_translations: 2 Korean row(s) have no matching zh-Hans row"));
        CatalogSnapshotProvider provider = new CatalogSnapshotProvider(store, completeness, "zh-Hans, en");

        provider.run(new DefaultApplicationArguments());

        assertThat(provider.publishedLocales()).containsExactly("ko", "en");
        assertThat(provider.required("en")).isSameAs(english);
        assertThatThrownBy(() -> provider.required("zh-Hans"))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).code())
            .isEqualTo("LOCALE_NOT_READY");
        assertThat(output).contains("Locale zh-Hans is not published").contains("space_translations");
    }

    @Test
    void aLocaleThatFailsToLoadDoesNotKeepKoreanFromBeingServed() {
        CatalogSnapshot korean = emptySnapshot();
        UUID revision = korean.context().revisionId();
        when(store.loadPublished()).thenReturn(korean);
        when(completeness.findings(revision, "en")).thenReturn(List.of());
        when(store.loadRevision(revision, "en")).thenThrow(new IllegalStateException("missing en label"));
        CatalogSnapshotProvider provider = new CatalogSnapshotProvider(store, completeness, "ko,en");

        provider.run(new DefaultApplicationArguments());

        assertThat(provider.isReady()).isTrue();
        assertThat(provider.publishedLocales()).containsExactly("ko");
    }

    @Test
    void rejectsAnUnsupportedOrRepeatedConfiguredLocale() {
        assertThatThrownBy(() -> new CatalogSnapshotProvider(store, completeness, "ko,fr"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("fr");
        assertThatThrownBy(() -> new CatalogSnapshotProvider(store, completeness, "en,en"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> new CatalogSnapshotProvider(store, completeness, "ja"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("server messages");
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
