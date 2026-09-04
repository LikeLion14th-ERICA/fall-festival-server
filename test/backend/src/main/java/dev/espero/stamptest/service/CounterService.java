package dev.espero.stamptest.service;

import dev.espero.stamptest.domain.DomainModels.CounterResult;
import dev.espero.stamptest.persistence.CounterStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CounterService {

    private final CounterStore store;
    private final Clock clock;

    public CounterService(CounterStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public CounterResult adjust(UUID participantId, UUID operationId, int delta) {
        return store.adjust(participantId, operationId, delta, clock.instant());
    }
}
