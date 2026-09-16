package dev.espero.festival;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf"
)
class FallFestivalServerApplicationTests {

    @LocalServerPort
    private int port;

    @Test
    void exposesHealthEndpoint() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/healthz")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
            assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
        }
    }

    @Test
    void startsWithoutExternalServicesAndDoesNotExposeUnimplementedApi() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/festivals")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue("location")).isEmpty();
            assertThat(response.body()).doesNotContain("stackTrace", "exception");
        }
    }
}
