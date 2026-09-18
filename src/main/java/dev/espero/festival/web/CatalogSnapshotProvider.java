package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.CatalogSnapshotStore;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Holds the only catalog instance served by this process. A verified publish
 * becomes visible after a controlled restart; there is no public reload path.
 */
@Component
@Profile("db")
public class CatalogSnapshotProvider implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSnapshotProvider.class);

    private final CatalogSnapshotStore store;
    private final AtomicReference<CatalogSnapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<String> unavailableReason = new AtomicReference<>("Catalog has not loaded yet.");

    public CatalogSnapshotProvider(CatalogSnapshotStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            CatalogSnapshot loaded = store.loadPublished();
            snapshot.set(loaded);
            unavailableReason.set(null);
            log.info("Loaded published catalog snapshot: revision={}", loaded.context().revision());
        } catch (RuntimeException exception) {
            unavailableReason.set("Published catalog could not be loaded.");
            log.error("Published catalog snapshot was not loaded.", exception);
        }
    }

    public boolean isReady() {
        return snapshot.get() != null;
    }

    public CatalogSnapshot required() {
        CatalogSnapshot current = snapshot.get();
        if (current == null) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CATALOG_NOT_READY",
                "카탈로그를 아직 사용할 수 없습니다.",
                true
            );
        }
        return current;
    }

    public String unavailableReason() {
        return unavailableReason.get();
    }
}
