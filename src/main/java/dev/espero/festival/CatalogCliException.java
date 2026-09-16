package dev.espero.festival;

/** User-facing failure for the developer-only catalog CLI. */
public class CatalogCliException extends RuntimeException {

    public CatalogCliException(String message) {
        super(message);
    }

    public CatalogCliException(String message, Throwable cause) {
        super(message, cause);
    }
}
