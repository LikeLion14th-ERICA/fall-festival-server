import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class CatalogLoadGenerator {
    private static final int HIGHEST_STAGE_VUS = 500;
    private static final int READINESS_ATTEMPTS = 120;
    private static final int REQUEST_TIMEOUT_MS = 5_000;
    private static final String[] ROUTES = {
        "/api/v2/spaces",
        "/api/v2/spaces/space-001",
        "/api/v2/maps",
        "/api/v2/maps/map-area-1",
        "/api/v2/maps/map-area-1/pins?mapVersion=map-v1",
        "/api/v2/places/place-space-001",
        "/api/v2/ticket-guide"
    };

    private record Hit(String route, long nanos, int status, long bytes, String error) {
    }

    private static Map<String, String> parseArguments(String[] arguments) {
        Map<String, String> parsed = new HashMap<>();
        for (int index = 0; index < arguments.length - 1; index += 2) {
            parsed.put(arguments[index], arguments[index + 1]);
        }
        return parsed;
    }

    private static void writeStatus(Path path, String state) throws Exception {
        Path temporaryPath = Path.of(path + ".tmp-" + ProcessHandle.current().pid());
        String contents = "{\"status\":\"" + state + "\",\"at\":\""
            + Instant.now() + "\"}\n";
        Files.writeString(temporaryPath, contents);
        Files.move(
            temporaryPath,
            path,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        );
    }

    private static HttpResponse<byte[]> sendGet(HttpClient client, URI uri, int timeoutMs) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int countJsonIds(String value, String prefix) {
        String expression = "\\\"id\\\"\\s*:\\s*\\\"" + Pattern.quote(prefix);
        return (int) Pattern.compile(expression).matcher(value).results().count();
    }

    private static String formatTransportErrors(List<Hit> hits) {
        Map<String, Integer> errors = new TreeMap<>();
        for (Hit hit : hits) {
            if (!hit.error().isEmpty()) {
                errors.merge(hit.error(), 1, Integer::sum);
            }
        }

        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, Integer> entry : errors.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(String.format(
                Locale.ROOT,
                "\"%s\":%d",
                entry.getKey().replace("\"", "\\\""),
                entry.getValue()
            ));
        }
        return json.append('}').toString();
    }

    private static List<Hit> runStage(
        HttpClient client,
        String baseUrl,
        int virtualUsers,
        long durationMs,
        Path statusPath,
        String stageName,
        int timeoutMs
    ) throws Exception {
        writeStatus(statusPath, stageName);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(virtualUsers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Hit>>> futures = new ArrayList<>(virtualUsers);

        for (int workerIndex = 0; workerIndex < virtualUsers; workerIndex++) {
            final int worker = workerIndex;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();

                long deadline = System.nanoTime() + Duration.ofMillis(durationMs).toNanos();
                List<Hit> hits = new ArrayList<>();
                int routeIndex = worker % ROUTES.length;
                while (System.nanoTime() < deadline) {
                    String route = ROUTES[routeIndex++ % ROUTES.length];
                    long startedAt = System.nanoTime();
                    try {
                        HttpRequest request = HttpRequest.newBuilder(
                                URI.create(baseUrl + route)
                            )
                            .timeout(Duration.ofMillis(timeoutMs))
                            .GET()
                            .build();
                        HttpResponse<byte[]> response = client.send(
                            request,
                            HttpResponse.BodyHandlers.ofByteArray()
                        );
                        hits.add(new Hit(
                            route,
                            System.nanoTime() - startedAt,
                            response.statusCode(),
                            response.body().length,
                            ""
                        ));
                    } catch (HttpTimeoutException exception) {
                        hits.add(new Hit(
                            route,
                            System.nanoTime() - startedAt,
                            0,
                            0,
                            "TIMEOUT"
                        ));
                    } catch (Exception exception) {
                        hits.add(new Hit(
                            route,
                            System.nanoTime() - startedAt,
                            0,
                            0,
                            exception.getClass().getSimpleName()
                        ));
                    }
                }
                return hits;
            }));
        }

        ready.await(30, TimeUnit.SECONDS);
        start.countDown();

        List<Hit> allHits = new ArrayList<>();
        for (Future<List<Hit>> future : futures) {
            allHits.addAll(future.get());
        }
        executor.shutdown();
        return allHits;
    }

    private static String formatStage(
        String stageName,
        int virtualUsers,
        List<Hit> hits,
        long elapsedMs
    ) {
        int total = hits.size();
        long bytes = 0;
        int non2xx = 0;
        int timeouts = 0;
        StringBuilder endpointJson = new StringBuilder();

        for (String route : ROUTES) {
            List<Long> latencies = new ArrayList<>();
            List<Hit> routeHits = new ArrayList<>();
            long responseBytes = 0;
            int routeNon2xx = 0;
            int routeTimeouts = 0;

            for (Hit hit : hits) {
                if (!hit.route().equals(route)) {
                    continue;
                }
                latencies.add(hit.nanos());
                routeHits.add(hit);
                responseBytes += hit.bytes();
                if (hit.status() < 200 || hit.status() >= 300) {
                    routeNon2xx++;
                }
                if ("TIMEOUT".equals(hit.error())) {
                    routeTimeouts++;
                }
            }

            Collections.sort(latencies);
            double p95Ms = latencies.isEmpty()
                ? 0
                : latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1))
                    / 1e6;
            if (endpointJson.length() > 0) {
                endpointJson.append(',');
            }
            endpointJson.append(String.format(
                Locale.ROOT,
                "{\"endpoint\":\"%s\",\"count\":%d,"
                    + "\"throughputPerSecond\":%.3f,\"p95Ms\":%.3f,"
                    + "\"responseBytes\":%d,\"non2xx\":%d,\"timeout\":%d,"
                    + "\"transportErrors\":%s}",
                route.replace("\"", "\\\""),
                latencies.size(),
                latencies.size() / (elapsedMs / 1000.0),
                p95Ms,
                responseBytes,
                routeNon2xx,
                routeTimeouts,
                formatTransportErrors(routeHits)
            ));
        }

        for (Hit hit : hits) {
            bytes += hit.bytes();
            if (hit.status() < 200 || hit.status() >= 300) {
                non2xx++;
            }
            if ("TIMEOUT".equals(hit.error())) {
                timeouts++;
            }
        }

        return String.format(
            Locale.ROOT,
            "{\"name\":\"%s\",\"virtualUsers\":%d,\"durationMs\":%d,"
                + "\"total\":{\"count\":%d,\"responseBytes\":%d,"
                + "\"non2xx\":%d,\"timeout\":%d,\"transportErrors\":%s},"
                + "\"endpoints\":[%s]}",
            stageName,
            virtualUsers,
            elapsedMs,
            total,
            bytes,
            non2xx,
            timeouts,
            formatTransportErrors(hits),
            endpointJson
        );
    }

    public static void main(String[] rawArguments) throws Exception {
        Map<String, String> arguments = parseArguments(rawArguments);
        String baseUrl = arguments.get("--base-url");
        String output = arguments.get("--output");
        String statusPath = arguments.get("--status-file");
        String smokeReadyPath = arguments.get("--smoke-ready-file");
        long warmupMs = Long.parseLong(arguments.getOrDefault("--warmup-ms", "10000"));
        long stageMs = Long.parseLong(arguments.getOrDefault("--stage-ms", "30000"));
        int maxSupportedVUs = Integer.parseInt(
            arguments.getOrDefault("--max-supported-vus", "500")
        );
        if (maxSupportedVUs < HIGHEST_STAGE_VUS) {
            throw new IllegalArgumentException(
                "maxSupportedVUs must be >= " + HIGHEST_STAGE_VUS
            );
        }

        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        boolean ready = false;
        for (int attempt = 0; attempt < READINESS_ATTEMPTS && !ready; attempt++) {
            HttpResponse<byte[]> response = sendGet(
                client,
                URI.create(baseUrl + "/readyz"),
                REQUEST_TIMEOUT_MS
            );
            ready = response != null && response.statusCode() == 200;
            if (!ready) {
                Thread.sleep(500);
            }
        }
        if (!ready) {
            throw new IllegalStateException("Readiness gate failed");
        }

        List<HttpResponse<byte[]>> smokeResponses = new ArrayList<>(ROUTES.length);
        for (String route : ROUTES) {
            HttpResponse<byte[]> response = sendGet(
                client,
                URI.create(baseUrl + route),
                REQUEST_TIMEOUT_MS
            );
            if (response == null || response.statusCode() != 200) {
                throw new IllegalStateException("Smoke route failed: " + route);
            }
            smokeResponses.add(response);
        }

        String spacesBody = new String(smokeResponses.get(0).body(), StandardCharsets.UTF_8);
        String mapsBody = new String(smokeResponses.get(2).body(), StandardCharsets.UTF_8);
        String ticketBody = new String(smokeResponses.get(6).body(), StandardCharsets.UTF_8);
        int spaceCount = countJsonIds(spacesBody, "space-");
        int mapCount = countJsonIds(mapsBody, "map-");
        if (spaceCount != 100
            || mapCount != 7
            || !ticketBody.contains("place-ticket-zone")
            || !ticketBody.contains("map-overview")
            || !ticketBody.contains("pin-ticket-zone")
            || !ticketBody.contains("overview-v1")) {
            throw new IllegalStateException(
                "Smoke cardinality/content failed: spaces=" + spaceCount + " maps=" + mapCount
            );
        }

        Files.writeString(Path.of(smokeReadyPath), "smoke-ok\n");
        writeStatus(Path.of(statusPath), "smoke-ok");
        runStage(
            client,
            baseUrl,
            100,
            warmupMs,
            Path.of(statusPath),
            "warmup",
            REQUEST_TIMEOUT_MS
        );

        List<String> stages = new ArrayList<>();
        for (int virtualUsers : new int[]{100, 200, 500}) {
            long startedAt = System.currentTimeMillis();
            List<Hit> hits = runStage(
                client,
                baseUrl,
                virtualUsers,
                stageMs,
                Path.of(statusPath),
                "vus-" + virtualUsers,
                REQUEST_TIMEOUT_MS
            );
            stages.add(formatStage(
                "vus-" + virtualUsers,
                virtualUsers,
                hits,
                System.currentTimeMillis() - startedAt
            ));
        }

        writeStatus(Path.of(statusPath), "complete");
        String result = "{\"generatedAt\":\"" + Instant.now()
            + "\",\"baseUrl\":\"" + baseUrl
            + "\",\"fixture\":{\"spaces\":100,\"maps\":7,\"places\":101,\"pins\":207}"
            + ",\"loadGenerator\":{\"maxSupportedVUs\":" + maxSupportedVUs
            + ",\"implementation\":\"Java virtual-thread HttpClient\"}"
            + ",\"stages\":[" + String.join(",", stages) + "]}\n";
        Path temporaryOutput = Path.of(output + ".tmp-" + ProcessHandle.current().pid());
        Files.writeString(temporaryOutput, result, StandardCharsets.UTF_8);
        Files.move(
            temporaryOutput,
            Path.of(output),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        );
    }
}
