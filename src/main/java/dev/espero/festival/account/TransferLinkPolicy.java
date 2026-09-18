package dev.espero.festival.account;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates an optional transfer URL against the deployment's exact host allowlist. */
@Component
public class TransferLinkPolicy {

    private final Set<String> allowedHosts;

    public TransferLinkPolicy(OperationalAccountProperties properties) {
        this.allowedHosts = properties.getTransferLinkAllowedHosts().stream()
            .map(value -> {
                String normalized = normalizeHost(value);
                if (normalized == null) {
                    throw new OperationalAccountException("TRANSFER_LINK_ALLOWLIST_INVALID");
                }
                return normalized;
            })
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public String validateOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        try {
            URI uri = URI.create(normalized);
            String host = normalizeHost(uri.getHost());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || host == null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !allowedHosts.contains(host)) {
                throw new OperationalAccountException("TRANSFER_LINK_INVALID");
            }
            return uri.normalize().toASCIIString();
        } catch (OperationalAccountException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new OperationalAccountException("TRANSFER_LINK_INVALID");
        }
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0 || value.indexOf('@') >= 0
            || value.indexOf(':') >= 0) {
            return null;
        }
        try {
            String normalized = IDN.toASCII(value.strip(), IDN.USE_STD3_ASCII_RULES)
                .toLowerCase(Locale.ROOT);
            return normalized.isBlank() ? null : normalized;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
