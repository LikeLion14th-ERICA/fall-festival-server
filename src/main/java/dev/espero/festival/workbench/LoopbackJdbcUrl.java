package dev.espero.festival.workbench;

import java.util.Locale;
import java.util.Set;

/**
 * The workbench reaches the team database only through an approved SSH
 * tunnel, so every JDBC URL must point at a single loopback host.
 */
final class LoopbackJdbcUrl {

    private static final String PREFIX = "jdbc:postgresql://";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

    private LoopbackJdbcUrl() {}

    static void require(String url, String role) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            throw new IllegalStateException(role + " database URL must be a jdbc:postgresql:// URL.");
        }
        String rest = url.substring(PREFIX.length());
        int end = indexOfAny(rest, "/?");
        String hosts = end < 0 ? rest : rest.substring(0, end);
        if (hosts.isEmpty() || hosts.contains(",") || hosts.contains("@")) {
            throw new IllegalStateException(role + " database URL must name exactly one host.");
        }
        String host = hostWithoutPort(hosts).toLowerCase(Locale.ROOT);
        if (!LOOPBACK_HOSTS.contains(host)) {
            throw new IllegalStateException(
                role + " database URL must use a loopback host through the approved SSH tunnel."
            );
        }
    }

    private static String hostWithoutPort(String hostAndPort) {
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            return close < 0 ? hostAndPort : hostAndPort.substring(0, close + 1);
        }
        int colon = hostAndPort.indexOf(':');
        return colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
    }

    private static int indexOfAny(String value, String characters) {
        for (int index = 0; index < value.length(); index++) {
            if (characters.indexOf(value.charAt(index)) >= 0) {
                return index;
            }
        }
        return -1;
    }
}
