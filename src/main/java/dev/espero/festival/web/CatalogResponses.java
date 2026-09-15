package dev.espero.festival.web;

import java.math.BigDecimal;
import java.util.List;

/** API v2 response shapes for the published spaces and map catalog. */
public final class CatalogResponses {

    private CatalogResponses() {}

    public record Image(String url, String alt, int width, int height) {}

    public record Link(String label, String url, String target) {}

    public record Money(int amount, String currency) {}

    public record MenuItem(String name, Money price) {}

    public record MapTarget(String mapId, String placeId, String pinId, String mapVersion) {}

    public record Space(
        String id,
        String category,
        String name,
        Image image,
        String locationText,
        String operator,
        String hoursText,
        String description,
        Link contact,
        String experience,
        List<String> events,
        List<MenuItem> menu,
        MapTarget mapTarget
    ) {}

    public record Spaces(List<Space> items) {}

    public record MapInfo(String id, String name, String kind, String version, Image image) {}

    public record Maps(List<MapInfo> items, String overviewId) {}

    public sealed interface PinTarget permits PlacePinTarget, AreaPinTarget {}

    public record PlacePinTarget(String kind, String placeId) implements PinTarget {}

    public record AreaPinTarget(String kind, String mapId) implements PinTarget {}

    public record Pin(
        String id,
        String category,
        String label,
        BigDecimal x,
        BigDecimal y,
        PinTarget target
    ) {}

    public record Pins(String mapId, String mapVersion, List<Pin> items) {}

    public record Place(
        String id,
        String kind,
        String name,
        String locationText,
        String hoursText,
        String description,
        String usage,
        String spaceId
    ) {}
}
