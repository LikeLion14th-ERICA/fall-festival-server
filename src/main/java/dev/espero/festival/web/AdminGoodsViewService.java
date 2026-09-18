package dev.espero.festival.web;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsColorTranslation;
import dev.espero.festival.domain.GoodsSizeTranslation;
import dev.espero.festival.domain.GoodsTranslation;
import dev.espero.festival.persistence.GoodsStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Builds the administrator goods representation with every supported locale. */
@Service
@Profile("db")
public class AdminGoodsViewService {

    private static final ZoneId TIMEZONE = ZoneId.of("Asia/Seoul");
    private static final String CURRENCY = "KRW";
    private static final List<String> LOCALES = List.of("ko", "en", "zh-Hans", "ja");

    private final GoodsStore store;
    private final FestivalProperties properties;
    private final ApiMetaSupport metaSupport;

    public AdminGoodsViewService(GoodsStore store, FestivalProperties properties, ApiMetaSupport metaSupport) {
        this.store = store;
        this.properties = properties;
        this.metaSupport = metaSupport;
    }

    public AdminGoodsListSnapshot list(HttpServletRequest request) {
        List<AdminGoodsResponse> items = store.findAllForAdmin(properties.configuredFestivalId()).stream()
            .map(AdminGoodsViewService::toResponse)
            .toList();
        return new AdminGoodsListSnapshot(
            new AdminGoodsListResponse(items),
            metaSupport.unscopedMeta(request, "ko")
        );
    }

    private static AdminGoodsResponse toResponse(Goods goods) {
        List<AdminGoodsColorResponse> colors = goods.colors().stream()
            .map(color -> new AdminGoodsColorResponse(
                color.id().toString(),
                fullTranslations(
                    color.translations(),
                    translation -> new AdminGoodsColorResponse.Translation(translation.name())
                )
            ))
            .toList();
        List<AdminGoodsSizeResponse> sizes = goods.sizes().stream()
            .map(size -> new AdminGoodsSizeResponse(
                size.id().toString(),
                fullTranslations(
                    size.translations(),
                    translation -> new AdminGoodsSizeResponse.Translation(translation.label())
                )
            ))
            .toList();
        List<AdminGoodsCombinationResponse> combinations = goods.combinations().stream()
            .map(combination -> new AdminGoodsCombinationResponse(
                combination.id().toString(),
                combination.colorId() == null ? null : combination.colorId().toString(),
                combination.sizeId() == null ? null : combination.sizeId().toString(),
                combination.availability().name()
            ))
            .toList();
        return new AdminGoodsResponse(
            goods.id().toString(),
            goods.optionMode().name(),
            fullTranslations(
                goods.translations(),
                translation -> new AdminGoodsResponse.Translation(translation.name(), translation.description())
            ),
            new AdminGoodsResponse.Money(goods.priceAmount(), CURRENCY),
            colors,
            sizes,
            combinations,
            OffsetDateTime.ofInstant(goods.createdAt(), TIMEZONE),
            OffsetDateTime.ofInstant(goods.updatedAt(), TIMEZONE)
        );
    }

    private static <S, T> Map<String, T> fullTranslations(Map<String, S> sparse, Function<S, T> mapper) {
        Map<String, T> full = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            S translation = sparse.get(locale);
            full.put(locale, translation == null ? null : mapper.apply(translation));
        }
        return full;
    }

    public record AdminGoodsListSnapshot(AdminGoodsListResponse response, ApiMeta meta) {}
}
