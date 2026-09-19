package dev.espero.festival.goods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsAvailability;
import dev.espero.festival.domain.GoodsColor;
import dev.espero.festival.domain.GoodsCombination;
import dev.espero.festival.domain.GoodsOptionMode;
import dev.espero.festival.domain.GoodsSize;
import dev.espero.festival.domain.GoodsTranslation;
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

class GoodsUpdateServiceTest {

    private static final UUID FESTIVAL_ID = UUID.randomUUID();
    private static final Instant ORIGINAL_TIME = Instant.parse("2030-10-01T00:00:00Z");
    private static final Instant UPDATE_TIME = Instant.parse("2030-10-02T00:00:00Z");

    @Test
    void preservesMatchingOptionCombinationIdentityStatusAndTimestampAndCreatesOnlyNewPairs() {
        UUID colorA = UUID.randomUUID();
        UUID colorB = UUID.randomUUID();
        UUID sizeA = UUID.randomUUID();
        UUID sizeB = UUID.randomUUID();
        GoodsCombination retained = new GoodsCombination(
            UUID.randomUUID(), colorA, sizeA, GoodsAvailability.SOLD_OUT, ORIGINAL_TIME
        );
        GoodsCombination removed = new GoodsCombination(
            UUID.randomUUID(), colorB, sizeB, GoodsAvailability.ON_SALE, ORIGINAL_TIME
        );
        Goods current = goods(GoodsOptionMode.OPTIONS, List.of(retained, removed));
        GoodsInput input = optionsInput(
            List.of(color(colorB), color(colorA)),
            List.of(size(sizeB), size(sizeA)),
            List.of(new GoodsInput.OptionInput(colorA, sizeA), new GoodsInput.OptionInput(colorB, sizeA))
        );
        UUID newCombinationId = UUID.randomUUID();
        Fixture fixture = fixture(newCombinationId);

        Goods updated = fixture.service.update(FESTIVAL_ID, current, input, UPDATE_TIME);

        assertThat(updated.combinations()).hasSize(2);
        assertThat(updated.combinations().getFirst()).isEqualTo(retained);
        assertThat(updated.combinations().get(1)).satisfies(combination -> {
            assertThat(combination.id()).isEqualTo(newCombinationId);
            assertThat(combination.availability()).isEqualTo(GoodsAvailability.ON_SALE);
            assertThat(combination.updatedAt()).isEqualTo(UPDATE_TIME);
        });
        assertThat(updated.combinations()).doesNotContain(removed);
        verify(fixture.associations).replaceForUpdate(FESTIVAL_ID, current.id(), input.images(), UPDATE_TIME);
    }

    @Test
    void keepsOpaqueSingleCombinationWhenModeDoesNotChange() {
        GoodsCombination single = new GoodsCombination(
            UUID.randomUUID(), null, null, GoodsAvailability.SOLD_OUT, ORIGINAL_TIME
        );
        Goods current = goods(GoodsOptionMode.SINGLE, List.of(single));
        GoodsInput input = singleInput();
        Fixture fixture = fixture();

        Goods updated = fixture.service.update(FESTIVAL_ID, current, input, UPDATE_TIME);

        assertThat(updated.combinations()).containsExactly(single);
        assertThat(updated.updatedAt()).isEqualTo(UPDATE_TIME);
        assertThat(updated.createdAt()).isEqualTo(current.createdAt());
    }

    @Test
    void modeSwitchesReplaceEveryCombinationWithFreshOnSaleRows() {
        Goods single = goods(GoodsOptionMode.SINGLE, List.of(new GoodsCombination(
            UUID.randomUUID(), null, null, GoodsAvailability.SOLD_OUT, ORIGINAL_TIME
        )));
        UUID color = UUID.randomUUID();
        UUID size = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        Fixture toOptions = fixture(optionId);

        Goods options = toOptions.service.update(
            FESTIVAL_ID,
            single,
            optionsInput(
                List.of(color(color)), List.of(size(size)),
                List.of(new GoodsInput.OptionInput(color, size))
            ),
            UPDATE_TIME
        );
        assertThat(options.combinations()).singleElement().satisfies(combination -> {
            assertThat(combination.id()).isEqualTo(optionId);
            assertThat(combination.availability()).isEqualTo(GoodsAvailability.ON_SALE);
        });

        UUID singleId = UUID.randomUUID();
        Fixture toSingle = fixture(singleId);
        Goods replacedSingle = toSingle.service.update(FESTIVAL_ID, options, singleInput(), UPDATE_TIME.plusSeconds(1));
        assertThat(replacedSingle.combinations()).singleElement().satisfies(combination -> {
            assertThat(combination.id()).isEqualTo(singleId);
            assertThat(combination.colorId()).isNull();
            assertThat(combination.sizeId()).isNull();
            assertThat(combination.availability()).isEqualTo(GoodsAvailability.ON_SALE);
        });
    }

    @Test
    void rejectsForeignStableOptionIdsBeforeAnyMutation() {
        Goods current = goods(GoodsOptionMode.SINGLE, List.of(new GoodsCombination(
            UUID.randomUUID(), null, null, GoodsAvailability.ON_SALE, ORIGINAL_TIME
        )));
        UUID color = UUID.randomUUID();
        UUID size = UUID.randomUUID();
        GoodsInput input = optionsInput(
            List.of(color(color)), List.of(size(size)), List.of(new GoodsInput.OptionInput(color, size))
        );
        Fixture fixture = fixture(UUID.randomUUID());
        when(fixture.store.hasForeignOptionIds(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.update(FESTIVAL_ID, current, input, UPDATE_TIME))
            .isInstanceOf(GoodsOptionIdConflictException.class);
    }

    @Test
    void requiresTheCallerMutationTransaction() throws Exception {
        Transactional transactional = GoodsUpdateService.class
            .getMethod("update", UUID.class, Goods.class, GoodsInput.class, Instant.class)
            .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static Fixture fixture(UUID... generatedIds) {
        GoodsStore store = mock(GoodsStore.class);
        GoodsImageAssociationService associations = mock(GoodsImageAssociationService.class);
        AtomicReference<Goods> updated = new AtomicReference<>();
        doAnswer(invocation -> {
            updated.set(invocation.getArgument(1));
            return null;
        }).when(store).updateForEdit(any(Goods.class), any(Goods.class));
        when(store.findById(any(), any())).thenAnswer(invocation -> Optional.ofNullable(updated.get()));
        Queue<UUID> ids = new ArrayDeque<>(List.of(generatedIds));
        return new Fixture(store, associations, new GoodsUpdateService(store, associations, ids::remove));
    }

    private static Goods goods(GoodsOptionMode mode, List<GoodsCombination> combinations) {
        return new Goods(
            UUID.randomUUID(), FESTIVAL_ID, mode,
            Map.of("ko", new GoodsTranslation("상품", null), "en", new GoodsTranslation("Goods", null)),
            1000, List.of(), List.of(), List.of(), combinations, ORIGINAL_TIME, ORIGINAL_TIME
        );
    }

    private static GoodsInput singleInput() {
        return new GoodsInput(
            "SINGLE", translations(), new GoodsInput.PriceInput(2000, "KRW"),
            List.of(image()), List.of(), List.of(), List.of()
        );
    }

    private static GoodsInput optionsInput(
        List<GoodsInput.ColorInput> colors,
        List<GoodsInput.SizeInput> sizes,
        List<GoodsInput.OptionInput> options
    ) {
        return new GoodsInput(
            "OPTIONS", translations(), new GoodsInput.PriceInput(2000, "KRW"),
            List.of(image()), colors, sizes, options
        );
    }

    private static Map<String, GoodsInput.TranslationInput> translations() {
        Map<String, GoodsInput.TranslationInput> result = new LinkedHashMap<>();
        result.put("ko", new GoodsInput.TranslationInput("수정 상품", null));
        result.put("en", new GoodsInput.TranslationInput("Updated goods", null));
        result.put("zh-Hans", null);
        result.put("ja", null);
        return result;
    }

    private static GoodsInput.ImageInput image() {
        Map<String, String> alt = new LinkedHashMap<>();
        alt.put("ko", "상품 이미지");
        alt.put("en", "Goods image");
        alt.put("zh-Hans", null);
        alt.put("ja", null);
        return new GoodsInput.ImageInput(UUID.randomUUID(), alt);
    }

    private static GoodsInput.ColorInput color(UUID id) {
        return new GoodsInput.ColorInput(id, Map.of(
            "ko", new GoodsInput.ColorTranslationInput("색상"),
            "en", new GoodsInput.ColorTranslationInput("Color")
        ));
    }

    private static GoodsInput.SizeInput size(UUID id) {
        return new GoodsInput.SizeInput(id, Map.of(
            "ko", new GoodsInput.SizeTranslationInput("크기"),
            "en", new GoodsInput.SizeTranslationInput("Size")
        ));
    }

    private record Fixture(
        GoodsStore store,
        GoodsImageAssociationService associations,
        GoodsUpdateService service
    ) {}
}
