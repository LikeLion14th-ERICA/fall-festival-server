package dev.espero.festival.push;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@Conditional(PushFirebaseUnconfiguredCondition.class)
class PushNotificationFallbackConfiguration {

    @Bean
    PushNotificationService pushNotificationService() {
        return new NoOpPushNotificationService();
    }
}
