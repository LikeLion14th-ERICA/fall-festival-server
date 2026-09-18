package dev.espero.festival.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalPayloadTest {

    @Test
    void sortsObjectFieldsAndNormalizesEquivalentNumbers() {
        CanonicalPayload first = CanonicalPayload.from(Map.of(
            "status", "OPEN",
            "limits", List.of(1, new BigDecimal("2.0")),
            "target", UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
        ));
        CanonicalPayload second = CanonicalPayload.from(Map.of(
            "target", UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf"),
            "limits", List.of(1L, new BigDecimal("2.00")),
            "status", "OPEN"
        ));

        assertThat(first.value()).isEqualTo(second.value());
        assertThat(Sha256.hex(first.value())).isEqualTo(Sha256.hex(second.value()));
    }

    @Test
    void preservesArrayOrderAndRejectsAmbiguousValues() {
        assertThat(CanonicalPayload.from(Map.of("items", List.of("A", "B"))).value())
            .isNotEqualTo(CanonicalPayload.from(Map.of("items", List.of("B", "A"))).value());
        assertThatThrownBy(() -> CanonicalPayload.from(Map.of("unsupported", new Object())))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
