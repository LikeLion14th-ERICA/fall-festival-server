package dev.espero.festival.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.persistence.AdminAuthStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapTest {

    private static final Instant NOW = Instant.parse("2030-10-01T03:00:00Z");

    @Test
    void createsOneAccountWhenDatabaseIsEmptyAndBothValuesExist(CapturedOutput output) {
        AdminAuthStore store = Mockito.mock(AdminAuthStore.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        when(store.countAccounts()).thenReturn(0L);
        AdminBootstrap bootstrap = bootstrap(store, encoder, " first-admin ", "bootstrap-secret");

        bootstrap.run(new DefaultApplicationArguments());

        ArgumentCaptor<AdminAccount> account = ArgumentCaptor.forClass(AdminAccount.class);
        verify(store).lockBootstrap();
        verify(store).insertAccount(account.capture());
        assertThat(account.getValue().username()).isEqualTo("first-admin");
        assertThat(account.getValue().authority()).isEqualTo("ADMIN");
        assertThat(account.getValue().enabled()).isTrue();
        assertThat(encoder.matches("bootstrap-secret", account.getValue().passwordHash())).isTrue();
        assertThat(account.getValue().passwordHash()).doesNotContain("bootstrap-secret");
        assertThat(output).doesNotContain("bootstrap-secret");
    }

    @Test
    void doesNotCreateAnotherAccountWhenOneAlreadyExists() {
        AdminAuthStore store = Mockito.mock(AdminAuthStore.class);
        when(store.countAccounts()).thenReturn(1L);

        bootstrap(store, new BCryptPasswordEncoder(4), "admin", "bootstrap-secret")
            .run(new DefaultApplicationArguments());

        verify(store, never()).insertAccount(any());
    }

    @Test
    void doesNothingUnlessBothBootstrapValuesExist() {
        AdminAuthStore store = Mockito.mock(AdminAuthStore.class);

        bootstrap(store, new BCryptPasswordEncoder(4), "admin", null)
            .run(new DefaultApplicationArguments());

        verify(store, never()).lockBootstrap();
        verify(store, never()).insertAccount(any());
    }

    private AdminBootstrap bootstrap(
        AdminAuthStore store,
        BCryptPasswordEncoder encoder,
        String username,
        String password
    ) {
        AdminAuthProperties properties = new AdminAuthProperties(
            Duration.ofMinutes(15), Duration.ofDays(7),
            "test-admin-jwt-signing-secret-at-least-32-bytes", "https://admin.test.invalid",
            username, password
        );
        return new AdminBootstrap(store, encoder, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
