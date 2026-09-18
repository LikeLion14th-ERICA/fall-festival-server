package dev.espero.festival;

import dev.espero.festival.context.FestivalProperties;
import java.time.Clock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** Non-web Spring entry point for the developer catalog workflow. */
@Configuration(proxyBeanMethods = false)
@Profile("catalog-cli")
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@EnableConfigurationProperties(FestivalProperties.class)
@ComponentScan(basePackages = "dev.espero.festival.persistence")
@Import({
    CatalogManifestValidator.class,
    CatalogManifestReader.class,
    CatalogRevisionService.class,
    CatalogCliRunner.class
})
public class CatalogCliApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(CatalogCliApplication.class)
                .profiles("db", "catalog-cli")
                .properties("spring.flyway.enabled=false")
                .web(WebApplicationType.NONE)
                .run(args);
            org.springframework.boot.SpringApplication.exit(context, () -> 0);
        } catch (CatalogCliException exception) {
            System.err.println("catalog-cli: " + exception.getMessage());
            if (context != null) {
                org.springframework.boot.SpringApplication.exit(context, () -> 1);
            }
            System.exit(1);
        }
    }

    @Bean
    Clock cliClock() {
        return Clock.systemUTC();
    }
}
