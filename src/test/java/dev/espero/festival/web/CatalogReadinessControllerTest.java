package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CatalogReadinessControllerTest {

    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final CatalogReadinessController controller = new CatalogReadinessController(snapshots);

    @Test
    void returnsOkWhenTheCatalogIsReady() {
        when(snapshots.isReady()).thenReturn(true);

        ResponseEntity<java.util.Map<String, String>> response = controller.readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ready");
    }

    @Test
    void returnsServiceUnavailableWhenTheCatalogIsNotReady() {
        when(snapshots.isReady()).thenReturn(false);

        ResponseEntity<java.util.Map<String, String>> response = controller.readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "not_ready");
    }
}
