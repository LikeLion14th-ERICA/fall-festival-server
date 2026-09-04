package dev.espero.stamptest.service;

import dev.espero.stamptest.config.PushProperties;
import dev.espero.stamptest.web.ApiException;
import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PushEndpointValidator {

    private final PushProperties properties;

    public PushEndpointValidator(PushProperties properties) {
        this.properties = properties;
    }

    public void validate(String endpoint, String p256dh, String auth) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw invalid("endpoint is not a valid URI.");
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
            throw invalid("endpoint must be an HTTPS URL without user information.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw invalid("endpoint must use the default HTTPS port.");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = properties.allowedEndpointHostSuffixes().stream()
            .anyMatch(rule -> hostMatches(normalizedHost, rule.toLowerCase(Locale.ROOT)));
        if (!allowed) {
            throw invalid("endpoint host is not in the configured Web Push allowlist.");
        }
        byte[] publicKey = decode(p256dh, "keys.p256dh");
        byte[] authenticationSecret = decode(auth, "keys.auth");
        if (publicKey.length != 65 || publicKey[0] != 0x04) {
            throw invalid("keys.p256dh must be an uncompressed P-256 public key.");
        }
        if (authenticationSecret.length != 16) {
            throw invalid("keys.auth must decode to 16 bytes.");
        }
    }

    private boolean hostMatches(String host, String rule) {
        if (rule.startsWith(".")) {
            return host.endsWith(rule) && host.length() > rule.length();
        }
        return host.equals(rule);
    }

    private byte[] decode(String value, String field) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " must use base64url encoding.");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_SUBSCRIPTION", message);
    }
}
