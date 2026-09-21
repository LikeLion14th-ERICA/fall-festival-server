package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.CatalogSnapshotStore;
import dev.espero.festival.persistence.LocaleCompletenessStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Holds the only catalog instances served by this process, one per published
 * locale. A verified publish becomes visible after a controlled restart;
 * there is no public reload path.
 *
 * <p>Korean is always published. Another locale from {@code PUBLIC_LOCALES}
 * is published only when its snapshot loads and nothing in it is missing
 * compared with Korean. An incomplete locale is left out and logged; it never
 * falls back to Korean text and never keeps Korean from being served.</p>
 */
@Component
@Profile("db")
public class CatalogSnapshotProvider implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSnapshotProvider.class);

    private final CatalogSnapshotStore store;
    private final LocaleCompletenessStore completeness;
    private final List<String> configuredLocales;
    private final AtomicReference<Map<String, CatalogSnapshot>> snapshots = new AtomicReference<>(Map.of());
    private final AtomicReference<String> unavailableReason = new AtomicReference<>("Catalog has not loaded yet.");

    public CatalogSnapshotProvider(
        CatalogSnapshotStore store,
        LocaleCompletenessStore completeness,
        @Value("${festival.public-locales:ko}") String configuredLocales
    ) {
        this.store = store;
        this.completeness = completeness;
        this.configuredLocales = PublicContentLocale.parseConfigured(configuredLocales);
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, CatalogSnapshot> loaded = new LinkedHashMap<>();
        try {
            CatalogSnapshot korean = store.loadPublished();
            loaded.put(PublicContentLocale.KOREAN, korean);
            log.info("Loaded published catalog snapshot: revision={}", korean.context().revision());
            for (String locale : configuredLocales) {
                if (!locale.equals(PublicContentLocale.KOREAN)) {
                    loadLocale(locale, korean).ifPresent(snapshot -> loaded.put(locale, snapshot));
                }
            }
            snapshots.set(Map.copyOf(loaded));
            unavailableReason.set(null);
            log.info("Published locales: {}", publishedLocales());
        } catch (RuntimeException exception) {
            unavailableReason.set("Published catalog could not be loaded.");
            log.error("Published catalog snapshot was not loaded: error_type={}", exception.getClass().getSimpleName());
        }
    }

    private Optional<CatalogSnapshot> loadLocale(String locale, CatalogSnapshot korean) {
        List<String> findings;
        CatalogSnapshot snapshot;
        try {
            findings = new ArrayList<>(completeness.findings(korean.context().revisionId(), locale));
            snapshot = store.loadRevision(korean.context().revisionId(), locale);
        } catch (RuntimeException exception) {
            log.warn(
                "Locale {} is not published: its catalog could not be loaded: error_type={}",
                locale,
                exception.getClass().getSimpleName()
            );
            return Optional.empty();
        }
        if (!findings.isEmpty()) {
            log.warn("Locale {} is not published: its content is incomplete: {}", locale, findings);
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    public boolean isReady() {
        return snapshots.get().containsKey(PublicContentLocale.KOREAN);
    }

    /** The Korean snapshot; public reads in another locale use {@link #required(String)}. */
    public CatalogSnapshot required() {
        return required(PublicContentLocale.KOREAN);
    }

    public CatalogSnapshot required(String locale) {
        Map<String, CatalogSnapshot> current = snapshots.get();
        if (!current.containsKey(PublicContentLocale.KOREAN)) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CATALOG_NOT_READY",
                "카탈로그를 아직 사용할 수 없습니다.",
                true
            );
        }
        CatalogSnapshot snapshot = current.get(locale);
        if (snapshot == null) {
            throw PublicContentLocale.localeNotReady();
        }
        return snapshot;
    }

    /**
     * Published locales in display order: Korean first, then the configured
     * order. Before the catalog loads only Korean is accepted.
     */
    public List<String> publishedLocales() {
        Map<String, CatalogSnapshot> current = snapshots.get();
        List<String> locales = new ArrayList<>();
        locales.add(PublicContentLocale.KOREAN);
        configuredLocales.stream()
            .filter(locale -> !locale.equals(PublicContentLocale.KOREAN) && current.containsKey(locale))
            .forEach(locales::add);
        return List.copyOf(locales);
    }

    public String unavailableReason() {
        return unavailableReason.get();
    }
}
