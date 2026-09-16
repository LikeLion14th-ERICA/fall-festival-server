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
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class CatalogLoadGenerator {
    private static final int HIGHEST_STAGE_VUS = 500;
    private static final int READINESS_ATTEMPTS = 120;
    private static final int REQUEST_TIMEOUT_MS = 5_000;
    private static final int MAX_FAILURE_SAMPLES_PER_STAGE = 20;
    private static final String[] ROUTES = {
        "/api/v2/spaces",
        "/api/v2/spaces/space-001",
        "/api/v2/maps",
        "/api/v2/maps/map-area-1",
        "/api/v2/maps/map-area-1/pins?mapVersion=map-v1",
        "/api/v2/places/place-space-001",
        "/api/v2/ticket-guide"
    };

    private record Hit(
        String route,
        int worker,
        long stageOffsetMs,
        long nanos,
        int status,
        long bytes,
        String error,
        String detail
    ) {
    }

    private record Stage(String name, List<Hit> hits) {
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

    private static boolean failed(Hit hit) {
        return !hit.error().isEmpty() || hit.status() < 200 || hit.status() >= 300;
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character <= 0x1f) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String exceptionDetail(Throwable exception) {
        StringBuilder detail = new StringBuilder();
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 3; depth++, current = current.getCause()) {
            if (detail.length() > 0) {
                detail.append(" <- ");
            }
            detail.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                detail.append(": ").append(message.strip().replace('\r', ' ').replace('\n', ' '));
            }
        }
        return detail.length() <= 512 ? detail.toString() : detail.substring(0, 512);
    }

    private static String formatFailureSamples(List<Hit> hits) {
        StringBuilder samples = new StringBuilder("[");
        int emitted = 0;
        for (Hit hit : hits) {
            if (!failed(hit) || emitted == MAX_FAILURE_SAMPLES_PER_STAGE) {
                continue;
            }
            if (emitted++ > 0) {
                samples.append(',');
            }
            String error = hit.error().isEmpty() ? "HTTP_" + hit.status() : hit.error();
            samples.append(String.format(
                Locale.ROOT,
                "{\"endpoint\":\"%s\",\"worker\":%d,\"stageOffsetMs\":%d,"
                    + "\"durationMs\":%.3f,\"status\":%d,\"error\":\"%s\",\"detail\":\"%s\"}",
                jsonEscape(hit.route()),
                hit.worker(),
                hit.stageOffsetMs(),
                hit.nanos() / 1e6,
                hit.status(),
                jsonEscape(error),
                jsonEscape(hit.detail())
            ));
        }
        return samples.append(']').toString();
    }

    private static void requireNoFailures(List<Stage> stages) {
        for (Stage stage : stages) {
            long transportErrors = stage.hits().stream().filter(hit -> !hit.error().isEmpty()).count();
            long httpErrors = stage.hits().stream()
                .filter(hit -> hit.error().isEmpty() && (hit.status() < 200 || hit.status() >= 300))
                .count();
            if (transportErrors != 0 || httpErrors != 0) {
                throw new IllegalStateException(
                    stage.name() + " recorded " + transportErrors + " transport error(s) and "
                        + httpErrors + " HTTP error response(s); load-results.json contains diagnostic samples."
                );
            }
        }
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
                jsonEscape(entry.getKey()),
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
        AtomicLong stageStartedAt = new AtomicLong();
        List<Future<List<Hit>>> futures = new ArrayList<>(virtualUsers);

        for (int workerIndex = 0; workerIndex < virtualUsers; workerIndex++) {
            final int worker = workerIndex;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();

                long deadline = stageStartedAt.get() + Duration.ofMillis(durationMs).toNanos();
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
                            worker,
                            TimeUnit.NANOSECONDS.toMillis(startedAt - stageStartedAt.get()),
                            System.nanoTime() - startedAt,
                            response.statusCode(),
                            response.body().length,
                            "",
                            ""
                        ));
                    } catch (HttpTimeoutException exception) {
                        hits.add(new Hit(
                            route,
                            worker,
                            TimeUnit.NANOSECONDS.toMillis(startedAt - stageStartedAt.get()),
                            System.nanoTime() - startedAt,
                            0,
                            0,
                            "TIMEOUT",
                            exceptionDetail(exception)
                        ));
                    } catch (Exception exception) {
                        hits.add(new Hit(
                            route,
                            worker,
                            TimeUnit.NANOSECONDS.toMillis(startedAt - stageStartedAt.get()),
                            System.nanoTime() - startedAt,
                            0,
                            0,
                            exception.getClass().getSimpleName(),
                            exceptionDetail(exception)
                        ));
                    }
                }
                return hits;
            }));
        }

        if (!ready.await(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            throw new IllegalStateException("Virtual users did not reach the stage start gate.");
        }
        stageStartedAt.set(System.nanoTime());
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
                jsonEscape(route),
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
                + "\"endpoints\":[%s],\"failureSamples\":%s}",
            jsonEscape(stageName),
            virtualUsers,
            elapsedMs,
            total,
            bytes,
            non2xx,
            timeouts,
            formatTransportErrors(hits),
            endpointJson,
            formatFailureSamples(hits)
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
        List<Stage> measuredStages = new ArrayList<>();
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
            measuredStages.add(new Stage("vus-" + virtualUsers, hits));
        }

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
        requireNoFailures(measuredStages);
        writeStatus(Path.of(statusPath), "complete");
    }
}
