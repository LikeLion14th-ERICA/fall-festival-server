package dev.espero.festival.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "festival.id=ec00912b-763f-4f8f-8f57-4bdfc389ccbf",
    "festival.admin-auth.allowed-origin=https://admin.test.invalid"
})
@Import(SecurityConfigurationTest.ProbeController.class)
class SecurityConfigurationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("jsonRequestBodyLimitFilter")
    private FilterRegistrationBean<?> jsonRequestBodyLimitFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(jsonRequestBodyLimitFilter.getFilter())
            .apply(springSecurity())
            .build();
    }

    @Test
    void leavesExistingPublicGetApisAnonymous() throws Exception {
        for (String path : new String[] {
            "/api/v2/crowding", "/api/v2/stamp-guide", "/api/v2/ticket-guide"
        }) {
            mockMvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("public"));
        }
    }

    @Test
    void rejectsUnauthenticatedAdminRequestWithApiEnvelope() throws Exception {
        mockMvc.perform(get("/api/v2/admin/security-probe"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.meta.revision").value(0))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    void permitsAdminAuthority() throws Exception {
        mockMvc.perform(get("/api/v2/admin/security-probe").with(user("admin").authorities(() -> "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(content().string("admin"));
    }

    @Test
    void rejectsInsufficientAuthorityWithApiEnvelope() throws Exception {
        mockMvc.perform(get("/api/v2/admin/security-probe").with(user("viewer").authorities(() -> "VIEWER")))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void doesNotCacheACorsRejectedAdminRequest() throws Exception {
        mockMvc.perform(post("/api/v2/admin/sessions")
                .header(HttpHeaders.ORIGIN, "https://evil.test.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void rejectsAnOverLimitJsonBodyBeforeItReachesAnAdminController() throws Exception {
        String body = "{\"value\":\"" + "x".repeat(64 * 1024) + "\"}";

        mockMvc.perform(post("/api/v2/admin/security-probe")
                .with(user("admin").authorities(() -> "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
            .andExpect(jsonPath("$.error.message").value("요청 본문은 64KiB 이하입니다."))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @RestController
    static class ProbeController {

        @GetMapping({"/api/v2/crowding", "/api/v2/stamp-guide", "/api/v2/ticket-guide"})
        String publicApi() {
            return "public";
        }

        @GetMapping("/api/v2/admin/security-probe")
        String adminApi() {
            return "admin";
        }

        @PostMapping(path = "/api/v2/admin/security-probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        String adminJsonApi(@RequestBody Map<String, Object> body) {
            return "admin";
        }
    }
}
