package dev.espero.festival.context;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FestivalContextStartupValidatorTest {

    @Test
    void acceptsConfiguredUuid() {
        assertThatCode(() -> new FestivalContextStartupValidator(
            new FestivalProperties("ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingFestivalId() {
        assertInvalid(null);
    }

    @Test
    void rejectsBlankFestivalId() {
        assertInvalid("   ");
    }

    @Test
    void rejectsMalformedFestivalId() {
        assertInvalid("not-a-uuid");
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> new FestivalContextStartupValidator(new FestivalProperties(value)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FESTIVAL_ID");
    }
}
