package dev.espero.festival.auth;

import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.persistence.AdminAuthStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("db")
public class AdminBootstrap implements ApplicationRunner {

    private final AdminAuthStore store;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuthProperties properties;
    private final Clock clock;

    public AdminBootstrap(
        AdminAuthStore store,
        PasswordEncoder passwordEncoder,
        AdminAuthProperties properties,
        Clock clock
    ) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        String username = properties.bootstrapUsername();
        String password = properties.bootstrapPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return;
        }

        store.lockBootstrap();
        if (store.countAccounts() != 0) {
            return;
        }
        Instant now = clock.instant();
        store.insertAccount(new AdminAccount(
            UUID.randomUUID(), username.strip(), passwordEncoder.encode(password), "ADMIN", true,
            now, now, null
        ));
    }
}
