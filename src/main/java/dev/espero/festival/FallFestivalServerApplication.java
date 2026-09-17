package dev.espero.festival;

import dev.espero.festival.account.OperationalAccountProperties;
import dev.espero.festival.context.FestivalProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties({FestivalProperties.class, OperationalAccountProperties.class})
public class FallFestivalServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FallFestivalServerApplication.class, args);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
