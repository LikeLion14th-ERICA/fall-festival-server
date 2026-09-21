package dev.espero.festival;

import dev.espero.festival.account.AccountSettingsCliRunner;
import dev.espero.festival.account.OperationalAccountException;
import dev.espero.festival.account.OperationalAccountProperties;
import dev.espero.festival.account.OperationalAccountSettingsService;
import dev.espero.festival.account.TransferLinkPolicy;
import dev.espero.festival.persistence.OperationalAccountStore;
import java.time.Clock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** Isolated non-web entry point for operational-account changes. Flyway is always disabled. */
@Configuration(proxyBeanMethods = false)
@Profile("account-settings-cli")
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@EnableConfigurationProperties(OperationalAccountProperties.class)
@Import({
    OperationalAccountStore.class,
    TransferLinkPolicy.class,
    OperationalAccountSettingsService.class,
    AccountSettingsCliRunner.class
})
public class AccountSettingsCliApplication {

    private AccountSettingsCliApplication() {}

    public static void main(String[] args) {
        ConfigurableApplicationContext context = null;
        int exitCode = 1;
        try {
            OperatorCliApplicationSupport.prepareLogging();
            SpringApplication application = new SpringApplication(AccountSettingsCliApplication.class);
            application.setAdditionalProfiles("db", "account-settings-cli");
            // Keep the property as a diagnostic signal, while the auto-configuration exclusion
            // above prevents an environment value from ever enabling Flyway for this writer.
            application.setDefaultProperties(java.util.Map.of("spring.flyway.enabled", "false"));
            application.setWebApplicationType(WebApplicationType.NONE);
            OperatorCliApplicationSupport.configure(application);
            context = application.run();
            context.getBean(AccountSettingsCliRunner.class).execute(args, System.out);
            exitCode = 0;
        } catch (OperationalAccountException exception) {
            System.err.println("account-settings-cli: " + exception.code());
        } catch (Exception exception) {
            // Do not expose datasource URLs, account input, SQL, or stack traces from this CLI.
            System.err.println("account-settings-cli: ACCOUNT_CLI_UNAVAILABLE");
        } finally {
            if (context != null) {
                int finalExitCode = exitCode;
                org.springframework.boot.SpringApplication.exit(context, () -> finalExitCode);
            }
        }
        System.exit(exitCode);
    }

    @Bean
    Clock accountCliClock() {
        return Clock.systemUTC();
    }
}
