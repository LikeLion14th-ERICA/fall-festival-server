package dev.espero.festival.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PostgresTestImagesTest {
    @Test
    void preservesPg16AndUsesOnlyTheApprovedReleaseImage() {
        assertThat(PostgresTestImages.image("false")).isEqualTo("postgres:16-alpine");
        assertThat(PostgresTestImages.image("true")).isEqualTo("postgres:17.11");
    }

    @Test
    void refusesAnAmbiguousReleaseSelection() {
        assertThatThrownBy(() -> PostgresTestImages.image("17"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
