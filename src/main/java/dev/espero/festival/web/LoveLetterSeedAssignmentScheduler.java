package dev.espero.festival.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Retries bound advance registrations when the opposite-gender pool was empty at claim time. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("db")
public class LoveLetterSeedAssignmentScheduler {
    private static final Logger log = LoggerFactory.getLogger(LoveLetterSeedAssignmentScheduler.class);
    private final LoveLetterService service;

    public LoveLetterSeedAssignmentScheduler(LoveLetterService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${festival.love-letter.seed-retry-ms:5000}",
        initialDelayString = "${festival.love-letter.seed-initial-delay-ms:5000}")
    public void assignPending() {
        for (var participantId : service.pendingSeededParticipants()) {
            try {
                service.assignSeeded(participantId);
            } catch (RuntimeException failure) {
                // The next sweep retries the same uncompleted row; never log personal data.
                log.warn("Love-letter seed assignment will retry after {}", failure.getClass().getSimpleName());
            }
        }
    }
}
