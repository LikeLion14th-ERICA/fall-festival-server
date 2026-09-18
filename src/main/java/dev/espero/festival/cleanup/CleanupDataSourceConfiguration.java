package dev.espero.festival.cleanup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("db")
@EnableConfigurationProperties(CleanupProperties.class)
class CleanupDataSourceConfiguration {
}
