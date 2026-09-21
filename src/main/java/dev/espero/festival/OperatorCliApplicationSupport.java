package dev.espero.festival;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;

/** Keeps local, non-web operator CLIs from printing framework configuration on failure. */
final class OperatorCliApplicationSupport {

    private OperatorCliApplicationSupport() {}

    static void configure(SpringApplication application) {
        // A CLI has its own explicit option parser. Do not let unrelated
        // --spring.* or --logging.* arguments reconfigure its process.
        application.setAddCommandLineProperties(false);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setListeners(application.getListeners().stream()
            .filter(listener -> !(listener instanceof LoggingApplicationListener))
            .toList());
    }

    static void prepareLogging() {
        // beforeInitialize installs Logback's suppress-all filter. Combined
        // with removing Boot's logging listener above, a caller's logging.*
        // environment cannot re-enable framework diagnostics in this CLI.
        new LogbackLoggingSystem(OperatorCliApplicationSupport.class.getClassLoader()).beforeInitialize();
    }
}
