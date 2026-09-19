package dev.espero.festival.goods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsAvailability;
import dev.espero.festival.media.GoodsImageAssociationService;
import dev.espero.festival.persistence.GoodsStore;
import dev.espero.festival.web.GoodsInput;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class GoodsCreationServiceTest {

    @Test
    void createsSingleWithOneOpaqueOnSaleCombinationAndAttachesImages() {
        GoodsInput input = singleInput();
        UUID goodsId = UUID.randomUUID();
        UUID combinationId = UUID.randomUUID();
        GoodsStore store = mock(GoodsStore.class);
        GoodsImageAssociationService associations = mock(GoodsImageAssociationService.class);
        AtomicReference<Goods> inserted = captureInserted(store);
        GoodsCreationService service = new GoodsCreationService(
            store, associations, ids(goodsId, combinationId)
        );
        Instant now = Instant.parse("2030-10-01T03:00:00Z");

        Goods created = service.create(UUID.randomUUID(), input, now);

        assertThat(created.id()).isEqualTo(goodsId);
        assertThat(created.colors()).isEmpty();
        assertThat(created.sizes()).isEmpty();
        assertThat(created.combinations()).singleElement().satisfies(combination -> {
            assertThat(combination.id()).isEqualTo(combinationId);
            assertThat(combination.colorId()).isNull();
            assertThat(combination.sizeId()).isNull();
            assertThat(combination.availability()).isEqualTo(GoodsAvailability.ON_SALE);
            assertThat(combination.updatedAt()).isEqualTo(now);
        });
        verify(associations).attachNew(created.festivalId(), goodsId, input.images(), now);
    }

    @Test
    void createsOnlyExplicitOptionsInInputOrder() {
        GoodsInput input = optionsInput();
        UUID goodsId = UUID.randomUUID();
        UUID combinationA = UUID.randomUUID();
        UUID combinationB = UUID.randomUUID();
        GoodsStore store = mock(GoodsStore.class);
        GoodsImageAssociationService associations = mock(GoodsImageAssociationService.class);
        captureInserted(store);
        GoodsCreationService service = new GoodsCreationService(
            store, associations, ids(goodsId, combinationA, combinationB)
        );

        Goods created = service.create(UUID.randomUUID(), input, Instant.EPOCH);

        assertThat(created.colors()).extracting(color -> color.id()).containsExactlyElementsOf(
            input.colors().stream().map(GoodsInput.ColorInput::id).toList()
        );
        assertThat(created.sizes()).extracting(size -> size.id()).containsExactlyElementsOf(
            input.sizes().stream().map(GoodsInput.SizeInput::id).toList()
        );
        assertThat(created.combinations()).hasSize(2);
        assertThat(created.combinations()).allMatch(c -> c.availability() == GoodsAvailability.ON_SALE);
        assertThat(created.combinations()).extracting(c -> c.colorId() + "/" + c.sizeId())
            .containsExactlyElementsOf(input.options().stream().map(o -> o.colorId() + "/" + o.sizeId()).toList());
    }

    @Test
    void requiresTheCallerMutationTransaction() throws Exception {
        Transactional transactional = GoodsCreationService.class
            .getMethod("create", UUID.class, GoodsInput.class, Instant.class)
            .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static AtomicReference<Goods> captureInserted(GoodsStore store) {
        AtomicReference<Goods> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return null;
        }).when(store).insertForCreation(org.mockito.ArgumentMatchers.any(Goods.class));
        when(store.findById(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> Optional.ofNullable(inserted.get()));
        return inserted;
    }

    private static java.util.function.Supplier<UUID> ids(UUID... values) {
        Queue<UUID> ids = new ArrayDeque<>(List.of(values));
        return ids::remove;
    }

    private static GoodsInput singleInput() {
        return new GoodsInput(
            "SINGLE", productTranslations(), new GoodsInput.PriceInput(1000, "KRW"),
            List.of(image()), List.of(), List.of(), List.of()
        );
    }

    private static GoodsInput optionsInput() {
        UUID colorA = UUID.randomUUID();
        UUID colorB = UUID.randomUUID();
        UUID sizeA = UUID.randomUUID();
        UUID sizeB = UUID.randomUUID();
        return new GoodsInput(
            "OPTIONS", productTranslations(), new GoodsInput.PriceInput(1000, "KRW"), List.of(image()),
            List.of(color(colorA), color(colorB)), List.of(size(sizeA), size(sizeB)),
            List.of(new GoodsInput.OptionInput(colorA, sizeA), new GoodsInput.OptionInput(colorB, sizeB))
        );
    }

    private static Map<String, GoodsInput.TranslationInput> productTranslations() {
        Map<String, GoodsInput.TranslationInput> result = new LinkedHashMap<>();
        result.put("ko", new GoodsInput.TranslationInput("상품", null));
        result.put("en", new GoodsInput.TranslationInput("Goods", null));
        result.put("zh-Hans", null);
        result.put("ja", null);
        return result;
    }

    private static GoodsInput.ImageInput image() {
        Map<String, String> alt = new LinkedHashMap<>();
        alt.put("ko", "상품 이미지");
        alt.put("en", "Product image");
        alt.put("zh-Hans", null);
        alt.put("ja", null);
        return new GoodsInput.ImageInput(UUID.randomUUID(), alt);
    }

    private static GoodsInput.ColorInput color(UUID id) {
        return new GoodsInput.ColorInput(id, Map.of(
            "ko", new GoodsInput.ColorTranslationInput("검정"),
            "en", new GoodsInput.ColorTranslationInput("Black")
        ));
    }

    private static GoodsInput.SizeInput size(UUID id) {
        return new GoodsInput.SizeInput(id, Map.of(
            "ko", new GoodsInput.SizeTranslationInput("M"),
            "en", new GoodsInput.SizeTranslationInput("M")
        ));
    }
}
