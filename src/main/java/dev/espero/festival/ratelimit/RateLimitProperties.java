package dev.espero.festival.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-client request limits for the single backend instance.
 *
 * <p>{@code trustedProxyHops} is the number of proxies in front of the server
 * that append to {@code X-Forwarded-For} (for example the Next.js proxy and
 * the hosting load balancer). With 0 the socket address is the client, which
 * is only right when nothing sits in front of the server.</p>
 */
@ConfigurationProperties(prefix = "festival.rate-limit")
public record RateLimitProperties(
    Boolean enabled,
    Integer trustedProxyHops,
    Policy publicRead,
    Policy admin,
    Policy adminLogin,
    Policy stampReceipt,
    Policy artistHyped
) {

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        trustedProxyHops = trustedProxyHops == null ? 0 : trustedProxyHops;
        if (trustedProxyHops < 0 || trustedProxyHops > 5) {
            throw new IllegalArgumentException("festival.rate-limit.trusted-proxy-hops must be between 0 and 5");
        }
        // A screen polls two dynamic resources every 15 seconds; bursts cover page loads.
        publicRead = publicRead == null ? new Policy(120, 4.0) : publicRead;
        admin = admin == null ? new Policy(60, 1.0) : admin;
        // Login and the public receipt check are guessing targets: 5 attempts, then one every 12 seconds.
        adminLogin = adminLogin == null ? new Policy(5, 1.0 / 12) : adminLogin;
        stampReceipt = stampReceipt == null ? new Policy(5, 1.0 / 12) : stampReceipt;
        // Anonymous Hyped clicks cannot consume the public read budget.
        artistHyped = artistHyped == null ? new Policy(120, 4.0) : artistHyped;
    }

    /** A token bucket: {@code capacity} requests at once, refilled at {@code refillPerSecond}. */
    public record Policy(int capacity, double refillPerSecond) {

        public Policy {
            if (capacity < 1 || !(refillPerSecond > 0)) {
                throw new IllegalArgumentException("A rate limit policy needs a positive capacity and refill rate");
            }
        }
    }
}
