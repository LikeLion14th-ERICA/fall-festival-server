package dev.espero.festival;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/** Keeps local, non-web operator CLIs from printing framework configuration on failure. */
final class OperatorCliApplicationSupport {

    private OperatorCliApplicationSupport() {}

    static void configure(SpringApplication application) {
        // A CLI has its own explicit option parser. Do not let unrelated
        // --spring.* or --logging.* arguments reconfigure its process.
        application.setAddCommandLineProperties(false);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.addListeners(new FrameworkLoggingSilencer());
    }

    static void prepareLogging() {
        // System properties take precedence over environment configuration. The
        // process is short-lived and emits its safe result directly to stdout.
        System.setProperty("logging.level.root", "OFF");
    }

    private static final class FrameworkLoggingSilencer
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            LoggingSystem.get(event.getSpringApplication().getClassLoader())
                .setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.OFF);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
