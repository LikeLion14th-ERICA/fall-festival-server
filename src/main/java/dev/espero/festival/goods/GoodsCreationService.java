package dev.espero.festival.goods;

import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsAvailability;
import dev.espero.festival.domain.GoodsColor;
import dev.espero.festival.domain.GoodsColorTranslation;
import dev.espero.festival.domain.GoodsCombination;
import dev.espero.festival.domain.GoodsOptionMode;
import dev.espero.festival.domain.GoodsSize;
import dev.espero.festival.domain.GoodsSizeTranslation;
import dev.espero.festival.domain.GoodsTranslation;
import dev.espero.festival.media.GoodsImageAssociationService;
import dev.espero.festival.persistence.GoodsStore;
import dev.espero.festival.web.GoodsInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates a complete goods aggregate inside the caller-owned mutation transaction. */
@Service
@Profile("db")
public class GoodsCreationService {

    private final GoodsStore store;
    private final GoodsImageAssociationService imageAssociations;
    private final Supplier<UUID> idGenerator;

    @Autowired
    public GoodsCreationService(GoodsStore store, GoodsImageAssociationService imageAssociations) {
        this(store, imageAssociations, UUID::randomUUID);
    }

    GoodsCreationService(
        GoodsStore store,
        GoodsImageAssociationService imageAssociations,
        Supplier<UUID> idGenerator
    ) {
        this.store = store;
        this.imageAssociations = imageAssociations;
        this.idGenerator = idGenerator;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Goods create(UUID festivalId, GoodsInput input, Instant now) {
        UUID goodsId = idGenerator.get();
        GoodsOptionMode optionMode = GoodsOptionMode.valueOf(input.optionMode());
        List<GoodsCombination> combinations = combinations(input, optionMode, now);
        Goods goods = new Goods(
            goodsId,
            festivalId,
            optionMode,
            translations(input.translations()),
            input.price().amount(),
            List.of(),
            colors(input.colors()),
            sizes(input.sizes()),
            combinations,
            now,
            now
        );
        store.insertForCreation(goods);
        imageAssociations.attachNew(festivalId, goodsId, input.images(), now);
        return store.findById(festivalId, goodsId)
            .orElseThrow(() -> new IllegalStateException("Created goods could not be read back"));
    }

    private List<GoodsCombination> combinations(
        GoodsInput input,
        GoodsOptionMode optionMode,
        Instant now
    ) {
        if (optionMode == GoodsOptionMode.SINGLE) {
            return List.of(new GoodsCombination(idGenerator.get(), null, null, GoodsAvailability.ON_SALE, now));
        }
        return input.options().stream()
            .map(option -> new GoodsCombination(
                idGenerator.get(),
                option.colorId(),
                option.sizeId(),
                GoodsAvailability.ON_SALE,
                now
            ))
            .toList();
    }

    private static Map<String, GoodsTranslation> translations(
        Map<String, GoodsInput.TranslationInput> input
    ) {
        Map<String, GoodsTranslation> result = new LinkedHashMap<>();
        input.forEach((locale, translation) -> {
            if (translation != null) {
                result.put(locale, new GoodsTranslation(translation.name(), translation.description()));
            }
        });
        return result;
    }

    private static List<GoodsColor> colors(List<GoodsInput.ColorInput> input) {
        List<GoodsColor> result = new ArrayList<>(input.size());
        for (GoodsInput.ColorInput color : input) {
            Map<String, GoodsColorTranslation> translations = new LinkedHashMap<>();
            color.translations().forEach((locale, translation) -> {
                if (translation != null) {
                    translations.put(locale, new GoodsColorTranslation(translation.name()));
                }
            });
            result.add(new GoodsColor(color.id(), translations));
        }
        return result;
    }

    private static List<GoodsSize> sizes(List<GoodsInput.SizeInput> input) {
        List<GoodsSize> result = new ArrayList<>(input.size());
        for (GoodsInput.SizeInput size : input) {
            Map<String, GoodsSizeTranslation> translations = new LinkedHashMap<>();
            size.translations().forEach((locale, translation) -> {
                if (translation != null) {
                    translations.put(locale, new GoodsSizeTranslation(translation.label()));
                }
            });
            result.add(new GoodsSize(size.id(), translations));
        }
        return result;
    }
}
