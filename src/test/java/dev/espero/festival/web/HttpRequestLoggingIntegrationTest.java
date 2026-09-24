package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf", "festival.rate-limit.enabled=false",
    "logging.file.name=target/logging-integration/application.jsonl",
    "logging.structured.json.add.release=logging-test"
})
@Import(HttpRequestLoggingIntegrationTest.Probe.class)
@ExtendWith(OutputCaptureExtension.class)
class HttpRequestLoggingIntegrationTest {
    @LocalServerPort int port;

    @Test
    void correlatesActualSecurityMvcAndStreamingResponsesWithoutPayloads(CapturedOutput output) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var success = get(client, "/api/v2/logging-probe/private-path?token=private-query");
            JsonNode successful = event(output, success);
            assertThat(successful.path("status").asInt()).isEqualTo(200);
            assertThat(successful.path("route").asString()).isEqualTo("/api/v2/logging-probe/{id}");
            assertThat(successful.path("completion").asString()).isEqualTo("completed");
            assertThat(successful.path("release").asString()).isEqualTo("logging-test");
            assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("target/logging-integration/application.jsonl")))
                .contains(success.headers().firstValue("X-Request-Id").orElseThrow());

            var denied = get(client, "/api/v2/admin/logging-probe");
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(event(output, denied).path("error_code").asString()).isEqualTo("UNAUTHORIZED");

            var failure = get(client, "/api/v2/logging-failure");
            JsonNode failed = event(output, failure);
            assertThat(failed.path("error_code").asString()).isEqualTo("INTERNAL_ERROR");
            assertThat(failed.path("completion").asString()).isEqualTo("failed");
            assertThat(failed.path("diagnostic").asString()).contains("IllegalStateException", "Probe.fail");

            var stream = get(client, "/api/v2/logging-stream");
            assertThat(stream.body()).isEqualTo("ok");
            assertThat(event(output, stream).path("completion").asString()).isEqualTo("completed");
            assertThat(output).doesNotContain("private-path", "private-query", "secret-exception-message", "client-controlled-id", "secret-cookie");
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("X-Request-Id", "client-controlled-id").header("Cookie", "test=secret-cookie")
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode event(CapturedOutput output, HttpResponse<?> response) throws Exception {
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        ObjectMapper mapper = new ObjectMapper();
        for (int attempt = 0; attempt < 200; attempt++) {
            var events = output.getOut().lines().filter(line -> line.startsWith("{"))
                .map(mapper::readTree).filter(json -> "http_request".equals(json.path("event").asString())
                    && id.equals(json.path("request_id").asString())).toList();
            if (!events.isEmpty()) {
                assertThat(events).hasSize(1);
                return events.getFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Missing completion log for request " + id);
    }

    @RestController
    static class Probe {
        @GetMapping("/api/v2/logging-probe/{id}") String success() { return "ok"; }
        @GetMapping("/api/v2/logging-failure") String fail() {
            throw new IllegalStateException("secret-exception-message");
        }
        @GetMapping("/api/v2/logging-stream") StreamingResponseBody stream() {
            return output -> output.write(new byte[] {'o', 'k'});
        }
    }
}
