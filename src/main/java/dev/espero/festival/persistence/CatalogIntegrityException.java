package dev.espero.festival.persistence;

/** Raised when a published catalog cannot be represented safely. */
public class CatalogIntegrityException extends RuntimeException {

    public CatalogIntegrityException(String message) {
        super(message);
    }
}
