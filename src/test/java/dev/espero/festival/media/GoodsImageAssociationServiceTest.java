package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.persistence.GoodsImageAssociationStore;
import dev.espero.festival.persistence.MediaAssetStore;
import dev.espero.festival.web.GoodsInput;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class GoodsImageAssociationServiceTest {

    @Mock
    private MediaAssetStore mediaAssets;

    @Mock
    private GoodsImageAssociationStore associations;

    @Test
    void locksInDeterministicOrderButPersistsDisplayOrder() {
        UUID festivalId = UUID.randomUUID();
        UUID goodsId = UUID.randomUUID();
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<GoodsInput.ImageInput> inputOrder = List.of(image(high), image(low));
        List<UUID> lockOrder = List.of(low, high);
        Instant attachedAt = Instant.parse("2030-10-01T00:00:00Z");
        when(mediaAssets.lockAttachableGoodsImages(festivalId, lockOrder)).thenReturn(lockOrder);
        when(associations.lockOwnedGoods(festivalId, goodsId)).thenReturn(true);
        when(mediaAssets.markGoodsImagesAttached(eq(festivalId), eq(List.of(high, low)), eq(attachedAt)))
            .thenReturn(2);

        new GoodsImageAssociationService(mediaAssets, associations)
            .attachNew(festivalId, goodsId, inputOrder, attachedAt);

        verify(mediaAssets).lockAttachableGoodsImages(festivalId, lockOrder);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GoodsImageAssociationInput>> commands = ArgumentCaptor.forClass(List.class);
        verify(associations).insertAssociations(eq(festivalId), eq(goodsId), commands.capture());
        assertThat(commands.getValue())
            .extracting(command -> command.mediaId() + "/" + command.sortOrder())
            .containsExactly(high + "/0", low + "/1");
        verify(associations).insertTranslations(any());
    }

    @Test
    void requiresTheCallersExistingTransaction() throws Exception {
        Transactional transactional = GoodsImageAssociationService.class.getMethod(
            "attachNew",
            UUID.class,
            UUID.class,
            List.class,
            Instant.class
        ).getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static GoodsInput.ImageInput image(UUID mediaId) {
        LinkedHashMap<String, String> alt = new LinkedHashMap<>();
        alt.put("ko", "상품 이미지");
        alt.put("en", "Product image");
        alt.put("zh-Hans", null);
        alt.put("ja", null);
        return new GoodsInput.ImageInput(mediaId, alt);
    }
}
