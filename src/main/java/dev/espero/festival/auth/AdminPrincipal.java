package dev.espero.festival.auth;

import java.util.UUID;

public record AdminPrincipal(UUID adminId, String username, String authority) {}
