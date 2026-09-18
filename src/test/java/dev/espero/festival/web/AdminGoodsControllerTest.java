package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminGoodsControllerTest {

    @Test
    void includesFestivalInTheAvailabilityIdempotencyResourceIdentity() {
        UUID festivalId = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
        UUID otherFestivalId = UUID.fromString("4b028799-51c2-43fb-943c-f55ec5c60d99");
        UUID goodsId = UUID.fromString("f3219fa1-42e5-47a8-9bdf-3e7ca88c870f");
        UUID combinationId = UUID.fromString("940fa917-bbd0-4e8e-89a4-5a596659926e");

        String resourceId = AdminGoodsController.availabilityIdempotencyResourceId(
            festivalId,
            goodsId,
            combinationId
        );

        assertThat(resourceId).isEqualTo(festivalId + "/" + goodsId + "/" + combinationId);
        assertThat(AdminGoodsController.availabilityIdempotencyResourceId(
            otherFestivalId,
            goodsId,
            combinationId
        )).isNotEqualTo(resourceId);
    }
}
