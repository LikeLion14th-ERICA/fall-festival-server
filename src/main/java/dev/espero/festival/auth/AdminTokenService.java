package dev.espero.festival.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.espero.festival.domain.AdminAccount;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AdminTokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final AdminAuthProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public AdminTokenService(AdminAuthProperties properties, Clock clock) {
        this(properties, clock, new SecureRandom());
    }

    AdminTokenService(AdminAuthProperties properties, Clock clock, SecureRandom secureRandom) {
        this.properties = properties;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    public AccessToken issueAccessToken(AdminAccount account) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(account.id().toString())
            .claim("authority", account.authority())
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(signingSecret()));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Failed to sign admin access token", exception);
        }
        return new AccessToken(jwt.serialize(), expiresAt);
    }

    public Optional<AccessClaims> verifyAccessToken(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                || !jwt.verify(new MACVerifier(signingSecret()))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date issuedAt = claims.getIssueTime();
            Date expiresAt = claims.getExpirationTime();
            Instant now = clock.instant();
            String subject = claims.getSubject();
            if (subject == null || issuedAt == null || issuedAt.toInstant().isAfter(now.plusSeconds(30))
                || expiresAt == null || !expiresAt.toInstant().isAfter(now)) {
                return Optional.empty();
            }
            UUID adminId = UUID.fromString(subject);
            String authority = claims.getStringClaim("authority");
            if (!"ADMIN".equals(authority)) {
                return Optional.empty();
            }
            return Optional.of(new AccessClaims(adminId, authority, issuedAt.toInstant(), expiresAt.toInstant()));
        } catch (JOSEException | ParseException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String newRefreshToken() {
        byte[] value = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String hashRefreshToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] signingSecret() {
        String secret = properties.jwtSigningSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Admin JWT signing secret is not configured safely");
        }
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public record AccessToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "AccessToken[value=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }

    public record AccessClaims(UUID adminId, String authority, Instant issuedAt, Instant expiresAt) {}
}
