package dev.espero.festival.context;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.persistence.FestivalContextStore;
import dev.espero.festival.web.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class FestivalContextServiceTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private final FestivalContextStore store = mock(FestivalContextStore.class);
    private final FestivalContextService service = new FestivalContextService(
        store, new FestivalProperties(FESTIVAL_ID.toString())
    );

    @Test
    void reportsUnavailableWhenPublishedContextDoesNotExist() {
        when(store.findPublishedByFestivalId(FESTIVAL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(service::currentPublished)
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException apiException = (ApiException) exception;
                org.assertj.core.api.Assertions.assertThat(apiException.status().value()).isEqualTo(503);
                org.assertj.core.api.Assertions.assertThat(apiException.code())
                    .isEqualTo("FESTIVAL_CONTEXT_UNAVAILABLE");
                org.assertj.core.api.Assertions.assertThat(apiException.retryable()).isTrue();
            });
    }

    @Test
    void mapsDatabaseFailureToRetryableServiceUnavailable() {
        when(store.findPublishedByFestivalId(FESTIVAL_ID))
            .thenThrow(new DataAccessResourceFailureException("sensitive SQL detail"));

        assertThatThrownBy(service::currentPublished)
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException apiException = (ApiException) exception;
                org.assertj.core.api.Assertions.assertThat(apiException.status().value()).isEqualTo(503);
                org.assertj.core.api.Assertions.assertThat(apiException.code()).isEqualTo("SERVICE_UNAVAILABLE");
                org.assertj.core.api.Assertions.assertThat(apiException.getMessage())
                    .doesNotContain("sensitive SQL detail");
                org.assertj.core.api.Assertions.assertThat(apiException.retryable()).isTrue();
            });
    }
}
