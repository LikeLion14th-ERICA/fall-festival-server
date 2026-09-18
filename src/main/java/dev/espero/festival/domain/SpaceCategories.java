package dev.espero.festival.domain;

import java.util.Set;

/**
 * The booth and market categories shown as chips in the design, and which
 * of them present events or a menu. The public list filter accepts these
 * plus {@code ALL}.
 */
public final class SpaceCategories {

    public static final Set<String> ALL = Set.of(
        "PUB", "BOOTH", "FLEA_MARKET", "FOOD_TRUCK", "STUDENT_COUNCIL_BOOTH", "PROMOTION_BOOTH"
    );

    /** Booth-type spaces present hands-on events. */
    public static final Set<String> WITH_EVENTS = Set.of("BOOTH", "STUDENT_COUNCIL_BOOTH", "PROMOTION_BOOTH");

    /** Food-serving spaces present a menu. */
    public static final Set<String> WITH_MENU = Set.of("PUB", "FOOD_TRUCK");

    private SpaceCategories() {}
}
