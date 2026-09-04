package dev.espero.stamptest.web;

import dev.espero.stamptest.domain.DomainModels.SessionRecord;

public record AuthenticatedSession(SessionRecord value) {
    public static final String REQUEST_ATTRIBUTE = AuthenticatedSession.class.getName();
}
