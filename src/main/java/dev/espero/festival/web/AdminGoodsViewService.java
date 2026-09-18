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
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
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
    private final ConditionalResponseSupport conditionalResponses;

    public AdminGoodsViewService(
        GoodsStore store,
        FestivalProperties properties,
        ApiMetaSupport metaSupport,
        ConditionalResponseSupport conditionalResponses
    ) {
        this.store = store;
        this.properties = properties;
        this.metaSupport = metaSupport;
        this.conditionalResponses = conditionalResponses;
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

    public AdminGoodsSnapshot find(HttpServletRequest request, UUID goodsId) {
        Goods goods = store.findById(properties.configuredFestivalId(), goodsId)
            .orElseThrow(AdminGoodsViewService::notFound);
        return snapshot(request, goods);
    }

    public AdminGoodsSnapshot snapshot(HttpServletRequest request, Goods goods) {
        AdminGoodsResponse response = toResponse(goods);
        ApiMeta meta = metaSupport.unscopedMeta(request, "ko");
        String etag = conditionalResponses.strongEtag(
            new ConditionalApiResponse<>(response, ConditionalApiMeta.from(meta))
        );
        return new AdminGoodsSnapshot(goods, response, meta, etag);
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

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 정보를 찾을 수 없습니다.", false);
    }

    public record AdminGoodsListSnapshot(AdminGoodsListResponse response, ApiMeta meta) {}

    public record AdminGoodsSnapshot(Goods goods, AdminGoodsResponse response, ApiMeta meta, String etag) {}
}
