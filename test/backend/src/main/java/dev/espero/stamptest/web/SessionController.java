package dev.espero.stamptest.web;

import dev.espero.stamptest.config.SessionProperties;
import dev.espero.stamptest.service.SessionService;
import dev.espero.stamptest.service.SessionService.SessionAccess;
import dev.espero.stamptest.service.StateService;
import dev.espero.stamptest.web.ApiDtos.SessionRequest;
import dev.espero.stamptest.web.ApiDtos.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-api/session")
public class SessionController {

    private final SessionService sessions;
    private final StateService states;
    private final SessionProperties properties;
    private final ApiMapper mapper;
    private final Clock clock;

    public SessionController(
        SessionService sessions,
        StateService states,
        SessionProperties properties,
        ApiMapper mapper,
        Clock clock
    ) {
        this.sessions = sessions;
        this.states = states;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping
    public SessionResponse open(
        @Valid @RequestBody(required = false) SessionRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        SessionAccess access = sessions.open(
            request,
            body == null ? null : body.accessCode(),
            ClientIp.from(request)
        );
        long remainingSeconds = Math.max(
            1,
            Duration.between(clock.instant(), access.session().sessionExpiresAt()).toSeconds()
        );
        ResponseCookie cookie = ResponseCookie.from(properties.cookieName(), access.rawToken())
            .httpOnly(true)
            .secure(properties.cookieSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ofSeconds(remainingSeconds))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return new SessionResponse(
            access.session().csrfToken(),
            access.session().ownerKey(),
            mapper.state(states.get(access.session().participantId()))
        );
    }
}
