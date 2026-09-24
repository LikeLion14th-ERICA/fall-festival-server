package dev.espero.festival.web;

import java.util.List;

/** Dynamic public Hyped representation. */
public final class ArtistHypedResponse {

    private ArtistHypedResponse() {}

    public record Item(String artistId, long hypedCount) {}

    public record Summary(boolean hypedEnabled, List<Item> items) {
        public Summary {
            items = List.copyOf(items);
        }
    }

    public record Increment(String artistId, long hypedCount) {}
}
