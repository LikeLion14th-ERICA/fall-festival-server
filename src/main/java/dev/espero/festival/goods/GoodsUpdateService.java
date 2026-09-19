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

/** Updates a complete product while preserving stable option sale-state rows. */
@Service
@Profile("db")
public class GoodsUpdateService {

    private final GoodsStore store;
    private final GoodsImageAssociationService imageAssociations;
    private final Supplier<UUID> idGenerator;

    @Autowired
    public GoodsUpdateService(GoodsStore store, GoodsImageAssociationService imageAssociations) {
        this(store, imageAssociations, UUID::randomUUID);
    }

    GoodsUpdateService(
        GoodsStore store,
        GoodsImageAssociationService imageAssociations,
        Supplier<UUID> idGenerator
    ) {
        this.store = store;
        this.imageAssociations = imageAssociations;
        this.idGenerator = idGenerator;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Goods update(UUID festivalId, Goods current, GoodsInput input, Instant now) {
        List<UUID> colorIds = input.colors().stream().map(GoodsInput.ColorInput::id).toList();
        List<UUID> sizeIds = input.sizes().stream().map(GoodsInput.SizeInput::id).toList();
        if (store.hasForeignOptionIds(current.id(), colorIds, sizeIds)) {
            throw new GoodsOptionIdConflictException();
        }

        GoodsOptionMode newMode = GoodsOptionMode.valueOf(input.optionMode());
        Goods updated = new Goods(
            current.id(),
            festivalId,
            newMode,
            translations(input.translations()),
            input.price().amount(),
            current.images(),
            colors(input.colors()),
            sizes(input.sizes()),
            combinations(current, input, newMode, now),
            current.createdAt(),
            now
        );
        store.updateForEdit(current, updated);
        imageAssociations.replaceForUpdate(festivalId, current.id(), input.images(), now);
        return store.findById(festivalId, current.id())
            .orElseThrow(() -> new IllegalStateException("Updated goods could not be read back"));
    }

    private List<GoodsCombination> combinations(
        Goods current,
        GoodsInput input,
        GoodsOptionMode newMode,
        Instant now
    ) {
        if (current.optionMode() == newMode) {
            if (newMode == GoodsOptionMode.SINGLE) {
                if (current.combinations().size() != 1
                    || current.combinations().getFirst().colorId() != null
                    || current.combinations().getFirst().sizeId() != null) {
                    throw new IllegalStateException("Current SINGLE product combination is inconsistent");
                }
                return current.combinations();
            }
            Map<OptionPair, GoodsCombination> existing = new LinkedHashMap<>();
            current.combinations().forEach(combination -> existing.put(
                new OptionPair(combination.colorId(), combination.sizeId()), combination
            ));
            return input.options().stream().map(option -> {
                GoodsCombination retained = existing.get(new OptionPair(option.colorId(), option.sizeId()));
                return retained == null
                    ? new GoodsCombination(
                        idGenerator.get(), option.colorId(), option.sizeId(), GoodsAvailability.ON_SALE, now
                    )
                    : retained;
            }).toList();
        }
        if (newMode == GoodsOptionMode.SINGLE) {
            return List.of(new GoodsCombination(idGenerator.get(), null, null, GoodsAvailability.ON_SALE, now));
        }
        return input.options().stream().map(option -> new GoodsCombination(
            idGenerator.get(), option.colorId(), option.sizeId(), GoodsAvailability.ON_SALE, now
        )).toList();
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

    private record OptionPair(UUID colorId, UUID sizeId) {}
}
