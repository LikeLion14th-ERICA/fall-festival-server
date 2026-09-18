package dev.espero.festival.workbench;

import dev.espero.festival.CatalogManifestReader;
import dev.espero.festival.CatalogManifestValidator;
import dev.espero.festival.context.FestivalProperties;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;

/**
 * Local catalog workbench for the release operator.
 *
 * <p>It binds to {@code 127.0.0.1} only, whatever the environment says, and
 * prints a one-time session URL. The browser gets the session token through
 * the URL fragment, which is never sent to a server or a referrer.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile(CatalogWorkbenchApplication.PROFILE)
@EnableAutoConfiguration(
    exclude = FlywayAutoConfiguration.class,
    excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration"
    }
)
@EnableConfigurationProperties({FestivalProperties.class, WorkbenchProperties.class})
@Import({CatalogManifestValidator.class, CatalogManifestReader.class})
public class CatalogWorkbenchApplication {

    /** Keeps the main server's component scan from picking up the workbench. */
    static final String PROFILE = "catalog-workbench";
    static final String DEFAULT_PORT = "8790";

    public static void main(String[] args) {
        builder().run(args);
    }

    static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(CatalogWorkbenchApplication.class)
            .profiles(PROFILE)
            .web(WebApplicationType.SERVLET)
            .initializers(context -> {
                String port = context.getEnvironment().getProperty("CATALOG_WORKBENCH_PORT", DEFAULT_PORT);
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "catalog-workbench-forced",
                    Map.of(
                        "server.address", "127.0.0.1",
                        "server.port", port,
                        "spring.flyway.enabled", "false"
                    )
                ));
            });
    }

    @Bean
    WorkbenchSession workbenchSession() {
        return new WorkbenchSession();
    }

    @Bean
    FilterRegistrationBean<WorkbenchRequestGuard> workbenchRequestGuard(WorkbenchSession session) {
        FilterRegistrationBean<WorkbenchRequestGuard> registration =
            new FilterRegistrationBean<>(new WorkbenchRequestGuard(session));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean(destroyMethod = "destroy")
    WorkbenchRoleContexts workbenchRoleContexts(WorkbenchProperties properties, FestivalProperties festival) {
        return new WorkbenchRoleContexts(properties, festival.configuredFestivalId());
    }

    @Bean
    WorkbenchBackendCheck workbenchBackendCheck(WorkbenchProperties properties) {
        return new WorkbenchBackendCheck(properties.backendUrl());
    }

    @Bean
    WorkbenchController workbenchController(
        WorkbenchRoleContexts roles,
        CatalogManifestReader reader,
        WorkbenchBackendCheck backend,
        FestivalProperties festival
    ) {
        return new WorkbenchController(roles, reader, backend, festival.configuredFestivalId());
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> workbenchUrlPrinter(WorkbenchSession session) {
        return event -> System.out.println(
            "Catalog workbench: http://127.0.0.1:" + event.getWebServer().getPort() + "/#token=" + session.token()
        );
    }

    static ConfigurableApplicationContext run(String... args) {
        return builder().run(args);
    }
}
