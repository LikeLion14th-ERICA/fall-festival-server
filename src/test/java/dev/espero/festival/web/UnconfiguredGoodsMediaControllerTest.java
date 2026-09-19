package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.espero.festival.media.MediaStorageUnconfiguredCondition;
import dev.espero.festival.support.ApiMetaTestFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UnconfiguredGoodsMediaControllerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2030-10-01T03:00:00Z"), ZoneOffset.UTC);
    private final ApiMetaSupport metaSupport = ApiMetaTestFixtures.contentMetaSupport(clock);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UnconfiguredGoodsMediaController())
        .setControllerAdvice(new GlobalApiExceptionHandler(metaSupport))
        .build();

    @Test
    void answersBothImageRoutesWithTheMissingStorageReason() throws Exception {
        mvc.perform(multipart("/api/v2/admin/media/goods-images"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("MEDIA_STORAGE_UNCONFIGURED"))
            .andExpect(jsonPath("$.error.retryable").value(false));
        mvc.perform(get("/api/v2/media/goods-images/7c9a2b1e-2f9c-4f0a-9b53-2f4c3a0e6d22/320"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("MEDIA_STORAGE_UNCONFIGURED"));
    }

    @Test
    void appliesOnlyWhileTheStorageRootIsNotSet() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Probe.class);
        runner.run(context -> assertThat(context).hasSingleBean(Probe.class));
        runner.withPropertyValues("festival.media.storage-root=/var/lib/espero/media")
            .run(context -> assertThat(context).doesNotHaveBean(Probe.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(MediaStorageUnconfiguredCondition.class)
    static class Probe {}
}
