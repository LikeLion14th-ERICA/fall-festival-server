package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.media.MediaVariant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoodsMediaConditionalSupportTest {

    private final GoodsMediaConditionalSupport support = new GoodsMediaConditionalSupport();

    @Test
    void createsDeterministicVariantSpecificStrongEtags() {
        UUID mediaId = UUID.fromString("00000000-0000-4000-8000-000000000050");

        String master = support.strongEtag(mediaId, MediaVariant.MASTER);

        assertThat(master).matches("\"[0-9a-f]{64}\"");
        assertThat(support.strongEtag(mediaId, MediaVariant.MASTER)).isEqualTo(master);
        assertThat(support.strongEtag(mediaId, MediaVariant.THUMB_320)).isNotEqualTo(master);
        assertThat(support.strongEtag(mediaId, MediaVariant.THUMB_640)).isNotEqualTo(master);
    }

    @Test
    void appliesWeakComparisonAndWildcardMatching() {
        String etag = support.strongEtag(UUID.randomUUID(), MediaVariant.MASTER);

        assertThat(support.matches(etag, etag)).isTrue();
        assertThat(support.matches("W/" + etag, etag)).isTrue();
        assertThat(support.matches("\"other\", " + etag, etag)).isTrue();
        assertThat(support.matches("*", etag)).isTrue();
        assertThat(support.matches("\"other\"", etag)).isFalse();
    }
}
