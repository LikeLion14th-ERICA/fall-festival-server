package dev.espero.festival.web;

/**
 * The stable part of API metadata used in a conditional representation.
 *
 * <p>Request IDs and server timestamps are deliberately response headers for
 * conditional responses because they change on every request. Keeping them
 * out of this value makes a strong ETag describe the representation rather
 * than the request that fetched it.</p>
 */
public record ConditionalApiMeta(
    String timezone,
    String festivalId,
    long revision,
    String locale,
    boolean mock
) {

    public static ConditionalApiMeta from(ApiMeta meta) {
        if (meta == null) {
            throw new IllegalArgumentException("API metadata is required");
        }
        return new ConditionalApiMeta(
            meta.timezone(),
            meta.festivalId(),
            meta.revision(),
            meta.locale(),
            meta.mock()
        );
    }
}
