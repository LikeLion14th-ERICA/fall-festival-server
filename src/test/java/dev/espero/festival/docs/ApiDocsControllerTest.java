package dev.espero.festival.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ApiDocsControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void targetsThisServerAndLabelsOnlyTheOperationsItDoesNotRoute() {
        ApiDocsController controller = new ApiDocsController(() -> Set.of(
            ApiDocsController.route("GET", "/api/v2/spaces/{id}"),
            ApiDocsController.route("GET", "/api/v2/config")
        ));

        JsonNode spec = json.readTree(controller.openApi().getBody());

        assertThat(spec.path("servers").get(0).path("url").asString()).isEqualTo("/");
        assertThat(spec.at("/paths/~1api~1v2~1spaces~1{spaceId}/get/summary").asString())
            .doesNotStartWith(ApiDocsController.NOT_IMPLEMENTED);
        assertThat(spec.at("/paths/~1api~1v2~1config/get/summary").asString())
            .doesNotStartWith(ApiDocsController.NOT_IMPLEMENTED);
        assertThat(spec.at("/paths/~1api~1v2~1stamp-receipt-verifications/post/summary").asString())
            .startsWith(ApiDocsController.NOT_IMPLEMENTED);
    }

    @Test
    void servesOnlyTheKnownSwaggerUiAssets() {
        ApiDocsController controller = new ApiDocsController(Set::of);

        assertThat(controller.asset("swagger-ui-bundle.js").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.asset("swagger-ui.css").getBody()).isNotEmpty();
        assertThat(controller.asset("package.json").getStatusCode().value()).isEqualTo(404);
    }

    @Nested
    @SpringBootTest(properties = {
        "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
        "festival.api-docs.enabled=true"
    })
    class Enabled {

        @Autowired
        private WebApplicationContext context;

        @Test
        void servesThePageAndTheSpecWithTheRealRouteTable() throws Exception {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            mvc.perform(get("/docs"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/docs/init.js")));
            String body = mvc.perform(get("/docs/openapi.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            JsonNode spec = JsonMapper.builder().build().readTree(body);
            // The docs routes themselves are real; stamp receipt verification is still mock-only.
            assertThat(spec.at("/paths/~1api~1v2~1stamp-receipt-verifications/post/summary").asString())
                .startsWith(ApiDocsController.NOT_IMPLEMENTED);
            mvc.perform(get("/webjars/swagger-ui/index.html")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @SpringBootTest(properties = "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf")
    class DisabledByDefault {

        @Autowired
        private WebApplicationContext context;

        @Test
        void hidesTheDocsPage() throws Exception {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            mvc.perform(get("/docs")).andExpect(status().isNotFound());
            mvc.perform(get("/docs/openapi.json")).andExpect(status().isNotFound());
        }
    }
}
