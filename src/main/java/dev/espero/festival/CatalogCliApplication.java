package dev.espero.festival;

import dev.espero.festival.context.FestivalProperties;
import java.time.Clock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
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
    CatalogExportService.class,
    CatalogCliRunner.class
})
public class CatalogCliApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = null;
        int exitCode = 1;
        try {
            OperatorCliApplicationSupport.prepareLogging();
            SpringApplication application = new SpringApplication(CatalogCliApplication.class);
            application.setAdditionalProfiles("db", "catalog-cli");
            application.setDefaultProperties(java.util.Map.of("spring.flyway.enabled", "false"));
            application.setWebApplicationType(WebApplicationType.NONE);
            OperatorCliApplicationSupport.configure(application);
            context = application.run(args);
            exitCode = 0;
        } catch (CatalogCliException exception) {
            System.err.println("catalog-cli: " + exception.getMessage());
        } catch (Exception exception) {
            // A failed local tool must not expose a datasource URL, credential,
            // manifest content, SQL, or a stack trace in an operator terminal.
            System.err.println("catalog-cli: CATALOG_CLI_UNAVAILABLE");
        } finally {
            if (context != null) {
                int finalExitCode = exitCode;
                org.springframework.boot.SpringApplication.exit(context, () -> finalExitCode);
            }
        }
        System.exit(exitCode);
    }

    @Bean
    Clock cliClock() {
        return Clock.systemUTC();
    }
}
