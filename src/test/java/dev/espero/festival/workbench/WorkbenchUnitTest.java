package dev.espero.festival.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkbenchUnitTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void acceptsOnlyASingleLoopbackDatabaseHost() {
        for (String url : List.of(
            "jdbc:postgresql://127.0.0.1:15432/festival",
            "jdbc:postgresql://localhost/festival?sslmode=require",
            "jdbc:postgresql://[::1]:5432/festival"
        )) {
            assertThatCode(() -> LoopbackJdbcUrl.require(url, "Export role")).as(url).doesNotThrowAnyException();
        }
        for (String url : List.of(
            "jdbc:postgresql://db.example.com:5432/festival",
            "jdbc:postgresql://127.0.0.1:5432,db.example.com:5432/festival",
            "jdbc:postgresql://user@127.0.0.1/festival",
            "jdbc:mysql://127.0.0.1/festival",
            "jdbc:postgresql://10.0.0.5/festival"
        )) {
            assertThatThrownBy(() -> LoopbackJdbcUrl.require(url, "Export role"))
                .as(url)
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void diffsListSectionsByRowIdentityAndIgnoresOrder() {
        JsonNode before = json.readTree("""
            {"festivalId": "a", "baselineRevisionId": "b",
             "spaces": [{"id": "one", "category": "PUB"}, {"id": "two", "category": "BOOTH"}],
             "spaceTranslations": [{"spaceId": "one", "locale": "ko", "name": "하나"}],
             "stampGuide": {"title": "스탬프"}}
            """);
        JsonNode after = json.readTree("""
            {"festivalId": "c", "baselineRevisionId": "d",
             "spaces": [{"id": "three", "category": "FOOD_TRUCK"}, {"id": "one", "category": "PUB"}],
             "spaceTranslations": [{"spaceId": "one", "locale": "ko", "name": "하나!"}],
             "stampGuide": {"title": "스탬프"}}
            """);

        List<ManifestDiff.Section> sections = ManifestDiff.diff(before, after);

        assertThat(sections).extracting(ManifestDiff.Section::name)
            .containsExactly("spaceTranslations", "spaces");
        assertThat(sections.get(0).changed()).containsExactly("locale=ko,spaceId=one");
        assertThat(sections.get(1).added()).containsExactly("three");
        assertThat(sections.get(1).removed()).containsExactly("two");
        assertThat(sections.get(1).changed()).isEmpty();
        assertThat(ManifestDiff.diff(before, before)).isEmpty();
    }

    @Test
    void guardRequiresTheExactHostTheSameOriginForWritesAndTheSessionToken() throws Exception {
        WorkbenchSession session = new WorkbenchSession();
        WorkbenchRequestGuard guard = new WorkbenchRequestGuard(session);

        assertThat(status(guard, request("GET", "/api/status", "127.0.0.1:8790", null, session.token())))
            .isEqualTo(200);
        assertThat(status(guard, request("GET", "/", "127.0.0.1:8790", null, null))).isEqualTo(200);
        assertThat(status(guard, request("GET", "/api/status", "localhost:8790", null, session.token())))
            .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(status(guard, request("GET", "/api/status", "attacker.example:8790", null, session.token())))
            .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(status(guard, request("POST", "/api/import", "127.0.0.1:8790", null, session.token())))
            .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(status(guard, request("POST", "/api/import", "127.0.0.1:8790",
            "http://evil.example", session.token()))).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(status(guard, request("POST", "/api/import", "127.0.0.1:8790",
            "http://127.0.0.1:8790", session.token()))).isEqualTo(200);
        assertThat(status(guard, request("GET", "/api/status", "127.0.0.1:8790", null, null)))
            .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(status(guard, request("GET", "/api/status", "127.0.0.1:8790", null, "wrong-token")))
            .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        MockHttpServletResponse response = new MockHttpServletResponse();
        guard.doFilter(request("GET", "/", "127.0.0.1:8790", null, null), response, new MockFilterChain());
        assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void acceptsOnlyAnHttpsOrLoopbackBackendUrl() {
        assertThat(new WorkbenchBackendCheck(null).configured()).isFalse();
        assertThat(new WorkbenchBackendCheck("https://festival.example").configured()).isTrue();
        assertThat(new WorkbenchBackendCheck("http://127.0.0.1:8080").configured()).isTrue();
        for (String url : List.of("http://festival.example", "https://user@festival.example", "ftp://127.0.0.1")) {
            assertThatThrownBy(() -> new WorkbenchBackendCheck(url)).as(url).isInstanceOf(IllegalStateException.class);
        }
    }

    private static int status(WorkbenchRequestGuard guard, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        guard.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private static MockHttpServletRequest request(String method, String uri, String host, String origin, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setLocalPort(8790);
        request.addHeader("Host", host);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (token != null) {
            request.addHeader(WorkbenchRequestGuard.TOKEN_HEADER, token);
        }
        return request;
    }
}
