package dev.espero.festival;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/** Reads a bounded local JSON manifest and computes its audit digest. */
@Component
public class CatalogManifestReader {

    private static final long MAX_MANIFEST_BYTES = 10L * 1024 * 1024;

    private final ObjectMapper mapper;
    private final CatalogManifestValidator validator;

    public CatalogManifestReader(CatalogManifestValidator validator) {
        this.validator = validator;
        this.mapper = JsonMapper.builder()
            .findAndAddModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();
    }

    public ManifestDocument read(Path path) {
        if (path == null) {
            throw new CatalogCliException("A manifest path is required.");
        }
        try {
            if (!Files.isRegularFile(path)) {
                throw new CatalogCliException("Manifest is not a regular local file: " + path);
            }
            long size = Files.size(path);
            if (size <= 0 || size > MAX_MANIFEST_BYTES) {
                throw new CatalogCliException("Manifest size must be between 1 byte and 10 MiB.");
            }
            byte[] bytes = Files.readAllBytes(path);
            CatalogManifest manifest = mapper.readValue(bytes, CatalogManifest.class);
            try {
                validator.validate(manifest);
            } catch (IllegalArgumentException exception) {
                throw new CatalogCliException("Manifest validation failed: " + exception.getMessage(), exception);
            }
            return new ManifestDocument(manifest, sha256(bytes));
        } catch (JacksonException exception) {
            throw new CatalogCliException("Manifest could not be read as valid JSON.", exception);
        } catch (IOException exception) {
            throw new CatalogCliException("Manifest could not be read as valid JSON.", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ManifestDocument(CatalogManifest manifest, String sha256) {}
}
