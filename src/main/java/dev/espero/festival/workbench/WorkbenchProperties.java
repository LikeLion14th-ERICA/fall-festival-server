package dev.espero.festival.workbench;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local workbench settings. The export and publish roles are separate
 * credentials; without publish credentials the workbench is export-only.
 * Credentials stay in this process and are never sent to the browser.
 *
 * @param export read-only catalog export role, required
 * @param publish catalog publish role, optional
 * @param backendUrl public backend checked after a publish, optional
 */
@ConfigurationProperties(prefix = "catalog.workbench")
public record WorkbenchProperties(Role export, Role publish, String backendUrl) {

    public record Role(String url, String username, String password) {

        boolean configured() {
            return hasText(url) || hasText(username) || hasText(password);
        }

        boolean complete() {
            return hasText(url) && hasText(username) && hasText(password);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
