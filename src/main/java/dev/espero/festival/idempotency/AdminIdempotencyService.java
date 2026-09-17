package dev.espero.festival.idempotency;

import dev.espero.festival.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes an administrator mutation once and retains its successful response.
 *
 * <p>Reservation commits in a short independent transaction. The ownership lock,
 * business mutation and completed response are then committed together. A retry
 * may take an expired lease only after the prior business transaction released
 * that row lock without committing.</p>
 */
@Service
@Profile("db")
public class AdminIdempotencyService {

    static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final AdminIdempotencyStore store;
    private final Clock clock;
    private final TransactionTemplate reservations;
    private final TransactionTemplate mutations;

    public AdminIdempotencyService(
        AdminIdempotencyStore store,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.store = store;
        this.clock = clock;
        this.reservations = new TransactionTemplate(transactionManager);
        this.reservations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mutations = new TransactionTemplate(transactionManager);
    }

    public IdempotencyExecution execute(IdempotencyRequest request, Supplier<IdempotencyResponse> mutation) {
        Objects.requireNonNull(request, "Idempotency request is required");
        Objects.requireNonNull(mutation, "Idempotent mutation is required");

        AdminIdempotencyStore.Reservation reservation = reservations.execute(status ->
            store.reserve(request, UUID.randomUUID(), clock.instant(), LEASE_DURATION));
        if (reservation instanceof AdminIdempotencyStore.Reservation.Replay replay) {
            return new IdempotencyExecution(replay.response(), true);
        }
        if (reservation == AdminIdempotencyStore.Reservation.InProgress.INSTANCE) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_IN_PROGRESS",
                "같은 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.",
                true
            );
        }
        if (reservation == AdminIdempotencyStore.Reservation.KeyReused.INSTANCE) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.",
                false
            );
        }

        AdminIdempotencyStore.Reservation.Acquired acquired =
            (AdminIdempotencyStore.Reservation.Acquired) reservation;
        try {
            return mutations.execute(status -> {
                store.lockOwned(request, acquired.leaseToken());
                IdempotencyResponse response = Objects.requireNonNull(mutation.get(), "Mutation response is required");
                store.complete(request, acquired.leaseToken(), response, clock.instant());
                return new IdempotencyExecution(response, false);
            });
        } catch (IdempotencyOwnershipLostException exception) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_IN_PROGRESS",
                "같은 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.",
                true
            );
        }
    }
}
