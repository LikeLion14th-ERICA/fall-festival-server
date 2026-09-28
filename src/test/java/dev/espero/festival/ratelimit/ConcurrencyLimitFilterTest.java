package dev.espero.festival.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.espero.festival.auth.ApiSecurityErrorWriter;
import jakarta.servlet.FilterChain;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConcurrencyLimitFilterTest {

    private final ApiSecurityErrorWriter errors = mock(ApiSecurityErrorWriter.class);

    @Test
    void rejectsQuicklyWithRetryableUnavailableWhenTheBulkheadIsFull() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(new OverloadProperties(true, 1, 1, 0L), errors);
        try (var held = hold(filter, "GET", "/api/v2/goods")) {
            MockHttpServletResponse rejected = perform(filter, "GET", "/api/v2/notices", new AtomicInteger());
            assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
            verify(errors).write(any(), eq(rejected), eq(503), eq("SERVICE_UNAVAILABLE"), anyString(), eq(true));
        }
        AtomicInteger passed = new AtomicInteger();
        perform(filter, "GET", "/api/v2/notices", passed);
        assertThat(passed).hasValue(1);
    }

    @Test
    void keepsHypedWritesInTheirOwnBulkhead() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(new OverloadProperties(true, 1, 1, 0L), errors);
        AtomicInteger passed = new AtomicInteger();
        try (var held = hold(filter, "POST", "/api/v2/artists/artist-a/hyped")) {
            perform(filter, "GET", "/api/v2/goods", passed);
            assertThat(passed).hasValue(1);
            perform(filter, "POST", "/api/v2/artists/artist-b/hyped", passed);
            assertThat(passed).hasValue(1);
        }
        verify(errors, times(1)).write(any(), any(), eq(503), anyString(), anyString(), anyBoolean());
    }

    @Test
    void neverLimitsAdminPreflightOrNonApiRequests() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(new OverloadProperties(true, 1, 1, 0L), errors);
        AtomicInteger passed = new AtomicInteger();
        try (var held = hold(filter, "GET", "/api/v2/goods")) {
            perform(filter, "GET", "/api/v2/admin/notices", passed);
            perform(filter, "OPTIONS", "/api/v2/goods", passed);
            perform(filter, "GET", "/healthz", passed);
        }
        assertThat(passed).hasValue(3);
        verify(errors, never()).write(any(), any(), anyInt(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void releasesTheSlotWhenTheRequestFails() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(new OverloadProperties(true, 1, 1, 0L), errors);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/goods");
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
            .isInstanceOf(IllegalStateException.class);
        AtomicInteger passed = new AtomicInteger();
        perform(filter, "GET", "/api/v2/goods", passed);
        assertThat(passed).hasValue(1);
    }

    @Test
    void rejectsInvalidBounds() {
        assertThatThrownBy(() -> new OverloadProperties(true, 0, 1, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OverloadProperties(true, 1, 1, 6_000L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletResponse perform(ConcurrencyLimitFilter filter, String method, String path,
        AtomicInteger passed) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, (req, res) -> passed.incrementAndGet());
        return response;
    }

    /** Occupies one slot on another thread until closed. */
    private static Held hold(ConcurrencyLimitFilter filter, String method, String path) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var task = executor.submit(() -> {
            filter.doFilter(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), (req, res) -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            return null;
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            task.get(5, TimeUnit.SECONDS);
            executor.shutdown();
        };
    }

    private interface Held extends AutoCloseable {
        @Override
        void close() throws Exception;
    }
}
