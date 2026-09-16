package dev.espero.stamptest.web;

import dev.espero.stamptest.config.ClockTestProperties;
import dev.espero.stamptest.config.PushProperties;
import dev.espero.stamptest.web.ApiDtos.PublicConfigResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-api/public-config")
public class PublicConfigController {

    private final PushProperties push;
    private final ClockTestProperties clockTest;

    public PublicConfigController(PushProperties push, ClockTestProperties clockTest) {
        this.push = push;
        this.clockTest = clockTest;
    }

    @GetMapping
    public PublicConfigResponse get() {
        return new PublicConfigResponse(
            push.publicKey(),
            push.configured(),
            clockTest.zone().getId(),
            Math.toIntExact(clockTest.duration().toMinutes()),
            clockTest.notificationCount()
        );
    }
}
