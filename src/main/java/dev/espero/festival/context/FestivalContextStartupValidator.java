package dev.espero.festival.context;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("db")
class FestivalContextStartupValidator {

    FestivalContextStartupValidator(FestivalProperties properties) {
        properties.configuredFestivalId();
    }
}
