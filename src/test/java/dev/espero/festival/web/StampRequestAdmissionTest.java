package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;

class StampRequestAdmissionTest {

    @Test
    void rejectsExcessWorkWithoutCallingItAndReleasesEveryConcurrentPermit() throws Exception {
        StampRequestAdmission admission = new StampRequestAdmission(4);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(index -> executor.submit(() ->
                admission.execute(() -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    return index;
                }))).toList();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> admission.execute(() -> {
                    throw new AssertionError("Rejected work must not acquire a DB connection");
                })).isInstanceOf(StampServiceUnavailableException.class).hasNoCause();
            } finally {
                release.countDown();
            }
            for (int index = 0; index < tasks.size(); index++) {
                assertThat(tasks.get(index).get(5, TimeUnit.SECONDS)).isEqualTo(index);
            }
        }
        assertThat(admission.execute(() -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void preservesDomainErrorsAndReleasesPermitsAfterDatabaseAndTransactionFailures() {
        StampRequestAdmission admission = new StampRequestAdmission(1);
        ApiException domain = new ApiException(HttpStatus.CONFLICT, "STAMP_CARD_FULL", "full", false);
        assertThatThrownBy(() -> admission.execute(() -> { throw domain; })).isSameAs(domain);
        for (RuntimeException failure : new RuntimeException[] {
            new QueryTimeoutException("private SQL must not reach the response"),
            new CannotCreateTransactionException("private DB address must not reach the response")
        }) {
            assertThatThrownBy(() -> admission.execute(() -> { throw failure; }))
                .isInstanceOf(StampServiceUnavailableException.class).hasCause(failure)
                .hasMessageNotContaining("private");
            assertThat(admission.execute(() -> true)).isTrue();
        }
        assertThatThrownBy(() -> admission.execute(() -> { throw new IllegalStateException("unexpected"); }))
            .isInstanceOf(IllegalStateException.class);
        assertThat(admission.execute(() -> true)).isTrue();
    }

    @Test
    void rejectsUnboundedOrDisabledConcurrencyConfiguration() {
        assertThatThrownBy(() -> new StampRequestAdmission(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StampRequestAdmission(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
