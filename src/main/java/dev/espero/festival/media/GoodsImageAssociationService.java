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

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceForUpdate(
        UUID festivalId,
        UUID goodsId,
        List<GoodsInput.ImageInput> images,
        Instant changedAt
    ) {
        List<GoodsImageAssociationInput> desired = commands(images);
        List<GoodsImageAssociationStore.LockedGoodsImage> current = lockAndValidateCurrent(festivalId, goodsId);
        List<UUID> currentIds = current.stream().map(GoodsImageAssociationStore.LockedGoodsImage::mediaId).toList();
        List<UUID> desiredIds = desired.stream().map(GoodsImageAssociationInput::mediaId).toList();
        List<UUID> added = desiredIds.stream().filter(id -> !currentIds.contains(id)).toList();
        List<UUID> removed = currentIds.stream().filter(id -> !desiredIds.contains(id)).toList();

        lockAndValidateAdded(festivalId, added);
        if (associations.deleteForGoods(festivalId, goodsId) != current.size()) {
            throw new IllegalStateException("Current goods image association count changed while locked");
        }
        associations.insertAssociations(festivalId, goodsId, desired);
        associations.insertTranslations(desired);
        if (!added.isEmpty() && mediaAssets.markGoodsImagesAttached(festivalId, added, changedAt) != added.size()) {
            throw new UnavailableGoodsImageException();
        }
        if (!removed.isEmpty() && mediaAssets.markGoodsImagesDetached(festivalId, removed, changedAt) != removed.size()) {
            throw new IllegalStateException("Removed goods media lifecycle could not be detached");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void detachAllForDelete(UUID festivalId, UUID goodsId, Instant detachedAt) {
        List<GoodsImageAssociationStore.LockedGoodsImage> current = lockAndValidateCurrent(festivalId, goodsId);
        List<UUID> mediaIds = current.stream().map(GoodsImageAssociationStore.LockedGoodsImage::mediaId).toList();
        if (associations.deleteForGoods(festivalId, goodsId) != current.size()) {
            throw new IllegalStateException("Current goods image association count changed while locked");
        }
        if (!mediaIds.isEmpty()
            && mediaAssets.markGoodsImagesDetached(festivalId, mediaIds, detachedAt) != mediaIds.size()) {
            throw new IllegalStateException("Deleted goods media lifecycle could not be detached");
        }
    }

    private List<GoodsImageAssociationInput> commands(List<GoodsInput.ImageInput> images) {
        if (images == null || images.isEmpty() || images.size() > 2
            || images.stream().anyMatch(image -> image == null || image.mediaId() == null || image.alt() == null)
            || new HashSet<>(images.stream().map(GoodsInput.ImageInput::mediaId).toList()).size() != images.size()) {
            throw new UnavailableGoodsImageException();
        }
        return IntStream.range(0, images.size())
            .mapToObj(index -> new GoodsImageAssociationInput(
                images.get(index).mediaId(), index, images.get(index).alt()
            ))
            .toList();
    }

    private List<GoodsImageAssociationStore.LockedGoodsImage> lockAndValidateCurrent(
        UUID festivalId,
        UUID goodsId
    ) {
        List<GoodsImageAssociationStore.LockedGoodsImage> current = associations.lockCurrent(festivalId, goodsId);
        boolean malformed = current.stream().anyMatch(image ->
            !festivalId.equals(image.festivalId())
                || !"GOODS_IMAGE".equals(image.purpose())
                || image.attachedAt() == null
                || image.detachedAt() != null);
        if (malformed) {
            throw new IllegalStateException("Current goods image lifecycle is inconsistent");
        }
        return current;
    }

    private void lockAndValidateAdded(UUID festivalId, List<UUID> added) {
        if (added.isEmpty()) {
            return;
        }
        List<UUID> locked = mediaAssets.lockAttachableGoodsImages(festivalId, added.stream().sorted().toList());
        if (locked.size() != added.size() || !new HashSet<>(locked).containsAll(added)) {
            throw new UnavailableGoodsImageException();
        }
    }
}
