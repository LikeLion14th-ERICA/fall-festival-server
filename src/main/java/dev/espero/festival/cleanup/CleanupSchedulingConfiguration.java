package dev.espero.festival.cleanup;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("db")
@Conditional(CleanupSchedulingCondition.class)
@EnableScheduling
class CleanupSchedulingConfiguration {
}
