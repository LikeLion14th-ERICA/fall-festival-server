package dev.espero.festival.media;

import dev.espero.festival.persistence.GoodsImageAssociationStore;
import dev.espero.festival.persistence.MediaAssetStore;
import dev.espero.festival.web.GoodsInput;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transaction-bound foundation for attaching uploaded media to a newly created goods row. */
@Service
@Profile("db")
public class GoodsImageAssociationService {

    private final MediaAssetStore mediaAssets;
    private final GoodsImageAssociationStore associations;

    public GoodsImageAssociationService(
        MediaAssetStore mediaAssets,
        GoodsImageAssociationStore associations
    ) {
        this.mediaAssets = mediaAssets;
        this.associations = associations;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void attachNew(
        UUID festivalId,
        UUID goodsId,
        List<GoodsInput.ImageInput> images,
        Instant attachedAt
    ) {
        Objects.requireNonNull(festivalId, "Festival id is required");
        Objects.requireNonNull(goodsId, "Goods id is required");
        Objects.requireNonNull(attachedAt, "Attachment time is required");
        if (images == null || images.isEmpty() || images.size() > 2
            || images.stream().anyMatch(image -> image == null || image.mediaId() == null || image.alt() == null)
            || new HashSet<>(images.stream().map(GoodsInput.ImageInput::mediaId).toList()).size() != images.size()) {
            throw new UnavailableGoodsImageException();
        }

        List<GoodsImageAssociationInput> commands = IntStream.range(0, images.size())
            .mapToObj(index -> new GoodsImageAssociationInput(
                images.get(index).mediaId(),
                index,
                images.get(index).alt()
            ))
            .toList();
        List<UUID> mediaIds = commands.stream().map(GoodsImageAssociationInput::mediaId).toList();
        List<UUID> lockOrder = mediaIds.stream().sorted().toList();

        List<UUID> locked = mediaAssets.lockAttachableGoodsImages(festivalId, lockOrder);
        if (locked.size() != mediaIds.size() || !new HashSet<>(locked).containsAll(mediaIds)) {
            throw new UnavailableGoodsImageException();
        }
        if (!associations.lockOwnedGoods(festivalId, goodsId)) {
            throw new UnavailableGoodsImageException();
        }

        associations.insertAssociations(festivalId, goodsId, commands);
        associations.insertTranslations(commands);
        if (mediaAssets.markGoodsImagesAttached(festivalId, mediaIds, attachedAt) != mediaIds.size()) {
            throw new UnavailableGoodsImageException();
        }
    }
}
