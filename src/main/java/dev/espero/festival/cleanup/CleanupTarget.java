package dev.espero.festival.cleanup;

/** A registered cleanup unit. Add a bean implementing this interface for a new table. */
public interface CleanupTarget {

    String name();

    CleanupTargetResult run(CleanupTargetContext context);
}
