package dev.espero.festival.support;

/** Root backend tests keep PG16 unless the PG17 release gate is explicitly selected. */
public final class PostgresTestImages {
    public static final String RELEASE_PROPERTY = "festival.test.postgres.release";
    public static final String POSTGRES_17 = "postgres:17.11";
    private static final String POSTGRES_16 = "postgres:16-alpine";

    private PostgresTestImages() {}

    public static String image() {
        return image(System.getProperty(RELEASE_PROPERTY, "false"));
    }

    static String image(String release) {
        return switch (release) {
            case "false" -> POSTGRES_16;
            case "true" -> POSTGRES_17;
            default -> throw new IllegalArgumentException(RELEASE_PROPERTY + " must be true or false");
        };
    }
}
