package dev.espero.festival;

import dev.espero.festival.persistence.NoticeTemplateStore;
import dev.espero.festival.template.NoticeTemplateCliException;
import dev.espero.festival.template.NoticeTemplateCliRunner;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** Isolated non-web entry point that replaces the notice templates. Flyway is always disabled. */
@Configuration(proxyBeanMethods = false)
@Profile("notice-template-cli")
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@Import({NoticeTemplateStore.class, NoticeTemplateCliRunner.class})
public class NoticeTemplateCliApplication {

    private NoticeTemplateCliApplication() {}

    public static void main(String[] args) {
        ConfigurableApplicationContext context = null;
        int exitCode = 1;
        try {
            context = new SpringApplicationBuilder(NoticeTemplateCliApplication.class)
                .profiles("db", "notice-template-cli")
                .properties("spring.flyway.enabled=false")
                .web(WebApplicationType.NONE)
                .run();
            context.getBean(NoticeTemplateCliRunner.class).execute(args, System.out);
            exitCode = 0;
        } catch (NoticeTemplateCliException exception) {
            System.err.println("notice-template-cli: " + exception.code());
        } catch (Exception exception) {
            // Do not expose datasource URLs, SQL or stack traces from this CLI.
            System.err.println("notice-template-cli: TEMPLATE_CLI_UNAVAILABLE");
        } finally {
            if (context != null) {
                int finalExitCode = exitCode;
                SpringApplication.exit(context, () -> finalExitCode);
            }
        }
        System.exit(exitCode);
    }

    @Bean
    Clock noticeTemplateCliClock() {
        return Clock.systemUTC();
    }
}
