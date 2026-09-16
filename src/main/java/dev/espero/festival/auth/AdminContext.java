package dev.espero.festival.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AdminContext {

    public AdminPrincipal requireCurrent() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof AdminPrincipal principal)
            || !"ADMIN".equals(principal.authority())) {
            throw new IllegalStateException("No authenticated administrator is available");
        }
        return principal;
    }
}
