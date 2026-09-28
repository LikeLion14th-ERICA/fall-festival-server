package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.push.PushNotificationService;
import dev.espero.festival.push.PushSubscriptionFailedException;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationSubscriptionControllerTest {

    private static final String PATH = "/api/v2/notification-subscriptions";

    private final PushNotificationService push = mock(PushNotificationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.systemMetaSupport(clock);
        mvc = MockMvcBuilders.standaloneSetup(new NotificationSubscriptionController(push, metaSupport))
            .setControllerAdvice(new GlobalApiExceptionHandler(metaSupport))
            .build();
    }

    @Test
    void subscribesAValidToken() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"device-token-1\"}"))
            .andExpect(status().isOk());

        verify(push).subscribe("device-token-1");
    }

    @Test
    void rejectsABlankToken() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"\"}"))
            .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(push);
    }

    @Test
    void rejectsAnUnknownExtraField() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"device-token-1\",\"extra\":true}"))
            .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(push);
    }

    @Test
    void answersServiceUnavailableWhenFirebaseRejectsTheToken() throws Exception {
        Mockito.doThrow(new PushSubscriptionFailedException(new RuntimeException("boom")))
            .when(push).subscribe("device-token-1");

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"device-token-1\"}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unconfiguredFirebaseDoesNotReportSubscribed() throws Exception {
        ApiMetaSupport metaSupport = ApiMetaTestFixtures.systemMetaSupport(Clock.systemUTC());
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new NotificationSubscriptionController(
            new dev.espero.festival.push.NoOpPushNotificationService(), metaSupport))
            .setControllerAdvice(new GlobalApiExceptionHandler(metaSupport)).build();
        var response = unconfigured.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"device-token-1\"}"))
            .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("SERVICE_UNAVAILABLE").doesNotContain("subscribed", "device-token-1");
    }
}
