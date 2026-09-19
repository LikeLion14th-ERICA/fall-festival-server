package dev.espero.festival.goods;

import dev.espero.festival.domain.Goods;
import dev.espero.festival.media.GoodsImageAssociationService;
import dev.espero.festival.persistence.GoodsStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Detaches product media and hard-deletes the aggregate in the caller transaction. */
@Service
@Profile("db")
public class GoodsDeletionService {

    private final GoodsStore store;
    private final GoodsImageAssociationService imageAssociations;

    public GoodsDeletionService(GoodsStore store, GoodsImageAssociationService imageAssociations) {
        this.store = store;
        this.imageAssociations = imageAssociations;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(UUID festivalId, Goods current, Instant now) {
        imageAssociations.detachAllForDelete(festivalId, current.id(), now);
        store.delete(festivalId, current.id());
    }
}
