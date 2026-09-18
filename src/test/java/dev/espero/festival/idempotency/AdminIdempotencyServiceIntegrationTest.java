package dev.espero.festival.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("db")
@Testcontainers(disabledWithoutDocker = true)
class AdminIdempotencyServiceIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("7309ad07-bdf0-42ff-a547-155ce180e117");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AdminIdempotencyService service;

    @Autowired
    private AdminIdempotencyStore store;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcTemplate().execute("""
            CREATE TABLE IF NOT EXISTS idempotency_test_effects (
                id UUID PRIMARY KEY,
                marker VARCHAR(64) NOT NULL
            )
            """);
        jdbc.getJdbcTemplate().execute("DELETE FROM idempotency_test_effects");
        jdbc.getJdbcTemplate().execute("DELETE FROM admin_idempotency_records");
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void commitsTheBusinessMutationAndCompletedResponseTogetherThenReplaysIt() {
        IdempotencyRequest request = request("crowding-duplicate", "RELAXED");

        IdempotencyExecution first = service.execute(request, () -> {
            insertEffect("first");
            return jsonResponse("{\"saved\":true}");
        });
        IdempotencyExecution replay = service.execute(request, () -> {
            insertEffect("must-not-run");
            return jsonResponse("{\"saved\":false}");
        });

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(effectCount()).isOne();
        AdminIdempotencyStore.StoredRecord stored = store.find(request).orElseThrow();
        assertThat(stored.isCompleted()).isTrue();
        assertThat(stored.response()).isEqualTo(first.response());
        assertThat(stored.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(rawSensitiveColumnCount()).isZero();
    }

    @Test
    void rejectsTheSameKeyWhenTheCanonicalRequestFingerprintDiffers() {
        IdempotencyRequest original = request("same-key", "RELAXED");
        service.execute(original, () -> IdempotencyResponse.noContent());

        assertThatThrownBy(() -> service.execute(request("same-key", "FULL"), IdempotencyResponse::noContent))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException api = (ApiException) exception;
                assertThat(api.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                assertThat(api.status().value()).isEqualTo(409);
            });
    }

    @Test
    void returnsConflictWithoutWaitingForAnInFlightBusinessTransaction() throws Exception {
        IdempotencyRequest request = request("in-progress", "CROWDED");
        CountDownLatch mutationEntered = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);

        Future<IdempotencyExecution> first = executor.submit(() -> service.execute(request, () -> {
            mutationEntered.countDown();
            await(releaseMutation);
            insertEffect("first");
            return IdempotencyResponse.noContent();
        }));
        assertThat(mutationEntered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.execute(request, IdempotencyResponse::noContent))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException api = (ApiException) exception;
                assertThat(api.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                assertThat(api.status().value()).isEqualTo(409);
            });

        releaseMutation.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).replayed()).isFalse();
        assertThat(effectCount()).isOne();
    }

    @Test
    void rollsBackTheBusinessMutationWhenCompletionCannotBeCommitted() {
        IdempotencyRequest request = request("rollback", "FULL");

        assertThatThrownBy(() -> service.execute(request, () -> {
            insertEffect("rolled-back");
            throw new IllegalStateException("simulate business failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(effectCount()).isZero();
        AdminIdempotencyStore.StoredRecord stored = store.find(request).orElseThrow();
        assertThat(stored.isCompleted()).isFalse();
        assertThat(stored.response()).isNull();
    }

    @Test
    void takesAnExpiredLeaseOnlyAfterThePriorWorkerHasNoCommittedMutation() {
        IdempotencyRequest request = request("expired-lease", "RELAXED");
        UUID originalLease = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        AdminIdempotencyStore.Reservation reservation = transaction.execute(status ->
            store.reserve(request, originalLease, Instant.now().minus(Duration.ofMinutes(5)), Duration.ofMinutes(1)));
        assertThat(reservation).isInstanceOf(AdminIdempotencyStore.Reservation.Acquired.class);

        IdempotencyExecution retry = service.execute(request, () -> {
            insertEffect("retry");
            return IdempotencyResponse.noContent();
        });

        assertThat(retry.replayed()).isFalse();
        assertThat(effectCount()).isOne();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> store.lockOwned(request, originalLease)))
            .isInstanceOf(IdempotencyOwnershipLostException.class);
    }

    private IdempotencyRequest request(String key, String status) {
        return new IdempotencyRequest(
            ADMIN_ID,
            "PUT",
            "/api/v2/admin/crowding",
            "2030-10-01",
            key,
            CanonicalPayload.from(Map.of("status", status))
        );
    }

    private IdempotencyResponse jsonResponse(String body) {
        return new IdempotencyResponse(200, "application/json", body);
    }

    private void insertEffect(String marker) {
        jdbc.update("INSERT INTO idempotency_test_effects (id, marker) VALUES (:id, :marker)",
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("marker", marker));
    }

    private long effectCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM idempotency_test_effects", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private long rawSensitiveColumnCount() {
        Long count = jdbc.queryForObject("""
            SELECT count(*)
            FROM information_schema.columns
            WHERE table_name = 'admin_idempotency_records'
              AND column_name IN ('admin_id', 'method', 'route', 'resource_id', 'idempotency_key', 'request_body')
            """, Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test mutation to release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the test mutation to release", exception);
        }
    }
}
