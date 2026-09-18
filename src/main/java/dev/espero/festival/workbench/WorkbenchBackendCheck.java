package dev.espero.festival.workbench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * After a publish, the release operator restarts the backend in a controlled
 * way. This read-only check then confirms {@code /readyz} and the revision
 * number the public API serves. It never restarts anything.
 */
class WorkbenchBackendCheck {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    private final URI baseUrl;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    WorkbenchBackendCheck(String backendUrl) {
        this.baseUrl = backendUrl == null || backendUrl.isBlank() ? null : validate(backendUrl);
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    boolean configured() {
        return baseUrl != null;
    }

    Result check(Long expectedRevisionNumber) {
        if (baseUrl == null) {
            throw new IllegalStateException("CATALOG_WORKBENCH_BACKEND_URL is not configured.");
        }
        int readyStatus = status("readyz");
        Long servedRevision = servedRevision();
        boolean matches = expectedRevisionNumber != null && expectedRevisionNumber.equals(servedRevision);
        return new Result(readyStatus, readyStatus == 200, servedRevision, expectedRevisionNumber, matches);
    }

    private int status(String path) {
        return send(path).statusCode();
    }

    private Long servedRevision() {
        HttpResponse<String> response = send("api/v2/maps");
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode revision = json.readTree(response.body()).path("meta").path("revision");
        return revision.isNumber() ? revision.asLong() : null;
    }

    private HttpResponse<String> send(String path) {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(path))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("Backend could not be reached.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Backend check was interrupted.");
        }
    }

    private static URI validate(String value) {
        URI uri = URI.create(value.endsWith("/") ? value : value + "/");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopbackHttp = scheme.equals("http") && LOOPBACK_HOSTS.contains(host);
        if (!(scheme.equals("https") || loopbackHttp) || host.isEmpty() || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("Backend URL must be https://, or http:// on a loopback host.");
        }
        return uri;
    }

    record Result(
        int readyzStatus,
        boolean ready,
        Long servedRevision,
        Long publishedRevision,
        boolean revisionMatches
    ) {}
}
