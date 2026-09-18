package dev.espero.festival.workbench;

import dev.espero.festival.CatalogExportService;
import dev.espero.festival.CatalogRevisionService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Starts one isolated database context per catalog role. The export context
 * uses a read-only pool; the publish context exists only when publish
 * credentials are configured. Both must reach the database through a
 * loopback SSH tunnel and must use different database users.
 */
class WorkbenchRoleContexts implements DisposableBean {

    private final ConfigurableApplicationContext exportContext;
    private final ConfigurableApplicationContext publishContext;

    WorkbenchRoleContexts(WorkbenchProperties properties, UUID festivalId) {
        WorkbenchProperties.Role export = properties.export();
        WorkbenchProperties.Role publish = properties.publish();
        if (export == null || !export.complete()) {
            throw new IllegalStateException("Export role URL, username and password are required.");
        }
        LoopbackJdbcUrl.require(export.url(), "Export role");
        boolean publishEnabled = publish != null && publish.configured();
        if (publishEnabled) {
            if (!publish.complete()) {
                throw new IllegalStateException("Publish role URL, username and password must be set together.");
            }
            LoopbackJdbcUrl.require(publish.url(), "Publish role");
            if (publish.username().equals(export.username())) {
                throw new IllegalStateException("Export and publish must use different database roles.");
            }
        }
        this.exportContext = start(export, festivalId, true);
        this.publishContext = publishEnabled ? start(publish, festivalId, false) : null;
    }

    CatalogExportService exports() {
        return exportContext.getBean(CatalogExportService.class);
    }

    WorkbenchRevisionQueries revisions() {
        return exportContext.getBean(WorkbenchRevisionQueries.class);
    }

    Optional<CatalogRevisionService> publisher() {
        return Optional.ofNullable(publishContext).map(context -> context.getBean(CatalogRevisionService.class));
    }

    @Override
    public void destroy() {
        if (publishContext != null) {
            publishContext.close();
        }
        exportContext.close();
    }

    private static ConfigurableApplicationContext start(
        WorkbenchProperties.Role role,
        UUID festivalId,
        boolean readOnly
    ) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("spring.datasource.url", role.url());
        settings.put("spring.datasource.username", role.username());
        settings.put("spring.datasource.password", role.password());
        settings.put("spring.datasource.hikari.maximum-pool-size", "2");
        settings.put("spring.datasource.hikari.read-only", Boolean.toString(readOnly));
        settings.put("spring.flyway.enabled", "false");
        settings.put("festival.id", festivalId.toString());
        return new SpringApplicationBuilder(WorkbenchRoleConfiguration.class)
            .profiles("db", WorkbenchRoleConfiguration.PROFILE)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .logStartupInfo(false)
            .registerShutdownHook(false)
            .initializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("catalog-workbench-role", settings)))
            .run();
    }
}
