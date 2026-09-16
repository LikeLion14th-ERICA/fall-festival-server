package dev.espero.festival.web;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness stays at /healthz; this endpoint reports public catalog readiness. */
@RestController
@Profile("db")
class CatalogReadinessController {

    private final CatalogSnapshotProvider snapshots;

    CatalogReadinessController(CatalogSnapshotProvider snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/readyz")
    ResponseEntity<Map<String, String>> readiness() {
        if (snapshots.isReady()) {
            return ResponseEntity.ok(Map.of("status", "ready"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "not_ready"));
    }
}
