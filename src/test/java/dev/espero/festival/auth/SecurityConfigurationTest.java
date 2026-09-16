package dev.espero.festival.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(SecurityConfigurationTest.ProbeController.class)
class SecurityConfigurationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    void permitsAdminAuthority() throws Exception {
        mockMvc.perform(get("/api/v2/admin/security-probe").with(user("admin").authorities(() -> "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string("admin"));
    }

    @Test
    void rejectsInsufficientAuthorityWithApiEnvelope() throws Exception {
        mockMvc.perform(get("/api/v2/admin/security-probe").with(user("viewer").authorities(() -> "VIEWER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
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
    }
}
