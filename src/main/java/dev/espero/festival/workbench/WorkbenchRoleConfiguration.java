package dev.espero.festival.workbench;

import dev.espero.festival.CatalogExportService;
import dev.espero.festival.CatalogManifestReader;
import dev.espero.festival.CatalogManifestValidator;
import dev.espero.festival.CatalogRevisionService;
import dev.espero.festival.context.FestivalProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * One non-web context per database role. The workbench starts one for the
 * export role and, when configured, a separate one for the publish role, so
 * the two roles never share a connection pool.
 */
@Configuration(proxyBeanMethods = false)
@Profile(WorkbenchRoleConfiguration.PROFILE)
@EnableAutoConfiguration(
    exclude = FlywayAutoConfiguration.class,
    excludeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration"
    }
)
@EnableConfigurationProperties(FestivalProperties.class)
@ComponentScan(basePackages = "dev.espero.festival.persistence")
@Import({
    CatalogManifestValidator.class,
    CatalogManifestReader.class,
    CatalogRevisionService.class,
    CatalogExportService.class,
    WorkbenchRevisionQueries.class
})
class WorkbenchRoleConfiguration {

    static final String PROFILE = "catalog-workbench-role";

    @Bean
    Clock workbenchClock() {
        return Clock.systemUTC();
    }
}
