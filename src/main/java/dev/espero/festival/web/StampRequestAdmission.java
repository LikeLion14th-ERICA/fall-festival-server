package dev.espero.festival.web;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** Bounds stamp work before the service proxy acquires a shared DB connection. */
@Component
@Profile("db")
public class StampRequestAdmission {

    private final Semaphore permits;

    public StampRequestAdmission(@Value("${festival.stamp.max-concurrent-requests:4}") int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("festival.stamp.max-concurrent-requests must be positive");
        }
        permits = new Semaphore(maximum);
    }

    public <T> T execute(Supplier<T> operation) {
        if (!permits.tryAcquire()) {
            throw new StampServiceUnavailableException(null);
        }
        try {
            // The proxied service commits or rolls back before returning this permit.
            return operation.get();
        } catch (DataAccessException | TransactionException exception) {
            throw new StampServiceUnavailableException(exception);
        } finally {
            permits.release();
        }
    }
}
