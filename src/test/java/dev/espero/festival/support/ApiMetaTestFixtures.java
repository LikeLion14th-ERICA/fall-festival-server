package dev.espero.festival.support;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.PublishedFestivalContext;
import dev.espero.festival.web.ApiMetaSupport;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

public final class ApiMetaTestFixtures {

    public static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    public static final UUID REVISION_ID = UUID.fromString("f109dca2-8b28-4e09-8114-beebc2bd3ea2");
    public static final PublishedFestivalContext PUBLISHED_CONTEXT = new PublishedFestivalContext(
        FESTIVAL_ID, REVISION_ID, 7, ZoneId.of("Asia/Seoul")
    );

    private ApiMetaTestFixtures() {}

    public static ApiMetaSupport contentMetaSupport(Clock clock) {
        return metaSupport(clock);
    }

    public static ApiMetaSupport systemMetaSupport(Clock clock) {
        return metaSupport(clock);
    }

    private static ApiMetaSupport metaSupport(Clock clock) {
        return new ApiMetaSupport(clock, new FestivalProperties(FESTIVAL_ID.toString()));
    }
}
