package dev.espero.festival.media;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "festival.media")
public class MediaStorageProperties {

    private String storageRoot;

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    Path configuredRoot() {
        if (storageRoot == null || storageRoot.isBlank()) {
            throw new IllegalStateException("FESTIVAL_MEDIA_STORAGE_ROOT must be configured");
        }
        return Path.of(storageRoot.strip());
    }
}
