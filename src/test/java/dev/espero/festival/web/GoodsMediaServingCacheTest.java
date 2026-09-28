package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.media.MediaVariant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoodsMediaServingCacheTest {

    @Test
    void dropsLeastRecentlyServedBytesToStayWithinBudget() {
        GoodsMediaServingCache cache = new GoodsMediaServingCache(10);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        cache.rememberBytes(first, MediaVariant.THUMB_320, new byte[4]);
        cache.rememberBytes(second, MediaVariant.THUMB_320, new byte[4]);
        assertThat(cache.bytes(first, MediaVariant.THUMB_320)).isPresent(); // first is now most recent
        cache.rememberBytes(third, MediaVariant.THUMB_320, new byte[4]);

        assertThat(cache.bytes(second, MediaVariant.THUMB_320)).isEmpty();
        assertThat(cache.bytes(first, MediaVariant.THUMB_320)).isPresent();
        assertThat(cache.bytes(third, MediaVariant.THUMB_320)).isPresent();
        assertThat(cache.cachedBytes()).isEqualTo(8);
    }

    @Test
    void neverKeepsVariantsLargerThanTheBudgetAndCanBeDisabled() {
        GoodsMediaServingCache cache = new GoodsMediaServingCache(3);
        UUID mediaId = UUID.randomUUID();
        assertThat(cache.fits(4)).isFalse();
        cache.rememberBytes(mediaId, MediaVariant.MASTER, new byte[4]);
        assertThat(cache.bytes(mediaId, MediaVariant.MASTER)).isEmpty();
        assertThat(new GoodsMediaServingCache(0).fits(1)).isFalse();
        assertThatThrownBy(() -> new GoodsMediaServingCache(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
