package dev.espero.festival.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class RateLimitTest {

    @Test
    void refillsTokensOverTimeAndReportsTheWait() {
        MutableClock clock = new MutableClock();
        RequestRateLimiter limiter = new RequestRateLimiter(clock);
        RateLimitProperties.Policy policy = new RateLimitProperties.Policy(2, 0.5);

        assertThat(limiter.acquire("p", policy, "a")).isZero();
        assertThat(limiter.acquire("p", policy, "a")).isZero();
        assertThat(limiter.acquire("p", policy, "a")).isEqualTo(2);
        assertThat(limiter.acquire("p", policy, "b")).isZero();

        clock.advanceMillis(2_000);
        assertThat(limiter.acquire("p", policy, "a")).isZero();
        assertThat(limiter.acquire("p", policy, "a")).isEqualTo(2);
    }

    @Test
    void keepsActiveBucketsWhenTheClientTableIsFull() {
        MutableClock clock = new MutableClock();
        RequestRateLimiter limiter = new RequestRateLimiter(clock, 2, 10_000_000_000L);
        RateLimitProperties.Policy policy = new RateLimitProperties.Policy(1, 0.000_001);

        assertThat(limiter.acquire("p", policy, "a")).isZero();
        assertThat(limiter.acquire("p", policy, "b")).isZero();
        assertThat(limiter.acquire("p", policy, "c")).isZero();

        assertThat(limiter.size()).isEqualTo(2);
        assertThat(limiter.acquire("p", policy, "a")).isPositive();
        assertThat(limiter.acquire("p", policy, "b")).isPositive();
        assertThat(limiter.acquire("p", policy, "d")).isPositive();
        assertThat(limiter.acquire("other-policy", policy, "d")).isZero();
        assertThat(limiter.acquire("other-policy", policy, "e")).isPositive();
        assertThat(limiter.size()).isEqualTo(2);
    }

    @Test
    void replacesOnlyIdleBucketsWhenTheClientTableIsFull() {
        MutableClock clock = new MutableClock();
        RequestRateLimiter limiter = new RequestRateLimiter(clock, 2, 10_000_000_000L);
        RateLimitProperties.Policy policy = new RateLimitProperties.Policy(1, 0.000_001);

        assertThat(limiter.acquire("p", policy, "active")).isZero();
        assertThat(limiter.acquire("p", policy, "idle")).isZero();
        clock.advanceMillis(9_000);
        assertThat(limiter.acquire("p", policy, "active")).isPositive();
        clock.advanceMillis(2_000);

        assertThat(limiter.acquire("p", policy, "replacement")).isZero();
        assertThat(limiter.size()).isEqualTo(2);
        assertThat(limiter.acquire("p", policy, "active")).isPositive();
        assertThat(limiter.acquire("p", policy, "idle")).isZero();
    }

    @Test
    void mapsRoutesToTheirPolicies() {
        assertThat(RateLimitFilter.policyName("POST", "/api/v2/stamp-receipt-verifications")).isEqualTo("stamp-receipt");
        assertThat(RateLimitFilter.policyName("POST", "/api/v2/admin/sessions")).isEqualTo("admin-login");
        assertThat(RateLimitFilter.policyName("POST", "/api/v2/admin/sessions/refresh")).isEqualTo("admin-login");
        assertThat(RateLimitFilter.policyName("PUT", "/api/v2/admin/crowding")).isEqualTo("admin");
        assertThat(RateLimitFilter.policyName("GET", "/api/v2/crowding")).isEqualTo("public-read");
        assertThat(RateLimitFilter.policyName("GET", "/api/v2/artist-hyped")).isEqualTo("public-read");
        assertThat(RateLimitFilter.policyName("POST", "/api/v2/artists/artist-a/hyped")).isEqualTo("artist-hyped");
        assertThat(RateLimitFilter.policyName("OPTIONS", "/api/v2/admin/crowding")).isNull();
        assertThat(RateLimitFilter.policyName("GET", "/readyz")).isNull();
    }

    @Test
    void takesTheClientFromTheTrustedProxyHops() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.2");

        assertThat(filter(0).client(request)).isEqualTo("10.0.0.9");
        assertThat(filter(1).client(request)).isEqualTo("198.51.100.2");
        assertThat(filter(2).client(request)).isEqualTo("203.0.113.7");
        // A chain shorter than the trusted hops did not pass every proxy.
        assertThat(filter(3).client(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void groupsIpv6ClientsBy64AfterSelectingTheTrustedProxyAddress() {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("2001:db8:1:2:3:4:5:6");
        MockHttpServletRequest sameSubnet = new MockHttpServletRequest();
        sameSubnet.setRemoteAddr("2001:0db8:0001:0002:ffff:ffff:ffff:ffff");
        MockHttpServletRequest otherSubnet = new MockHttpServletRequest();
        otherSubnet.setRemoteAddr("2001:db8:1:3::1");

        assertThat(filter(0).client(first)).isEqualTo(filter(0).client(sameSubnet));
        assertThat(filter(0).client(first)).isNotEqualTo(filter(0).client(otherSubnet));

        MockHttpServletRequest mappedIpv4 = new MockHttpServletRequest();
        mappedIpv4.setRemoteAddr("::ffff:192.0.2.1");
        MockHttpServletRequest ipv4 = new MockHttpServletRequest();
        ipv4.setRemoteAddr("192.0.2.1");
        assertThat(filter(0).client(mappedIpv4)).isEqualTo(filter(0).client(ipv4));

        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.setRemoteAddr("10.0.0.9");
        forwarded.addHeader("X-Forwarded-For", "2001:db8:1:2::1, 198.51.100.2");
        assertThat(filter(2).client(forwarded)).isEqualTo(filter(0).client(first));
        // A short chain still falls back to the socket address, which is also grouped.
        forwarded.setRemoteAddr("2001:db8:1:2::7");
        assertThat(filter(3).client(forwarded)).isEqualTo(filter(0).client(first));
    }

    private static RateLimitFilter filter(int hops) {
        return new RateLimitFilter(
            new RateLimitProperties(true, hops, null, null, null, null, null),
            new RequestRateLimiter(Clock.systemUTC()), null
        );
    }

    @Nested
    @SpringBootTest(properties = {
        "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
        "festival.rate-limit.public-read.capacity=2",
        "festival.rate-limit.public-read.refill-per-second=0.001",
        "festival.rate-limit.artist-hyped.capacity=2",
        "festival.rate-limit.artist-hyped.refill-per-second=0.001",
        "festival.rate-limit.stamp-receipt.capacity=1",
        "festival.rate-limit.stamp-receipt.refill-per-second=0.001"
    })
    class Enabled {

        @Autowired
        private WebApplicationContext context;

        @Test
        void answersTooManyRequestsWithTheErrorEnvelopeAndRetryAfter() throws Exception {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("rateLimitFilter", org.springframework.boot.web.servlet.FilterRegistrationBean.class)
                    .getFilter())
                .apply(springSecurity())
                .build();

            mvc.perform(get("/api/v2/lineup").with(remote("198.51.100.10"))).andExpect(status().isNotFound());
            mvc.perform(get("/api/v2/lineup").with(remote("198.51.100.10"))).andExpect(status().isNotFound());
            mvc.perform(get("/api/v2/lineup").with(remote("198.51.100.10")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.retryable").value(true));
            // Another client and unlimited routes are unaffected.
            mvc.perform(get("/api/v2/lineup").with(remote("198.51.100.11"))).andExpect(status().isNotFound());
            mvc.perform(get("/healthz").with(remote("198.51.100.10"))).andExpect(status().isOk());
            mvc.perform(post("/api/v2/stamp-receipt-verifications").with(remote("198.51.100.12")))
                .andExpect(status().is4xxClientError());
            mvc.perform(post("/api/v2/stamp-receipt-verifications").with(remote("198.51.100.12")))
                .andExpect(status().isTooManyRequests());
        }

        @Test
        void limitsAnonymousHypedWritesSeparatelyFromPublicReads() throws Exception {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("rateLimitFilter", org.springframework.boot.web.servlet.FilterRegistrationBean.class)
                    .getFilter())
                .apply(springSecurity())
                .build();

            String path = "/api/v2/artists/artist-a/hyped";
            mvc.perform(post(path).with(remote("198.51.100.29"))).andExpect(status().isNotFound());
            mvc.perform(post(path).with(remote("198.51.100.29"))).andExpect(status().isNotFound());
            mvc.perform(post(path).with(remote("198.51.100.29")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
            mvc.perform(get("/api/v2/artist-hyped").with(remote("198.51.100.29")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
        "festival.rate-limit.enabled=false"
    })
    class Disabled {

        @Autowired
        private WebApplicationContext context;

        @Test
        void registersNoLimiter() {
            assertThat(context.getBeanNamesForType(RequestRateLimiter.class)).isEmpty();
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remote(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2030-10-01T00:00:00Z");

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
