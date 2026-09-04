package dev.espero.stamptest.service;

import static dev.espero.stamptest.support.DbTime.value;

import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ExpiredParticipantCleanup {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ExpiredParticipantCleanup(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.session.cleanup-delay:PT1H}")
    @Transactional
    public int deleteExpired() {
        return jdbc.update(
            "DELETE FROM participants WHERE expires_at <= :now",
            Map.of("now", value(clock.instant()))
        );
    }
}
