package dev.espero.festival.web;

import dev.espero.festival.account.OperationalAccountPurpose;
import dev.espero.festival.account.OperationalAccountSetting;
import dev.espero.festival.account.OperationalAccountSettingsService;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsCombination;
import dev.espero.festival.domain.GoodsImage;
import dev.espero.festival.domain.GoodsImageTranslation;
import dev.espero.festival.domain.GoodsTranslation;
import dev.espero.festival.persistence.GoodsStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Builds the public goods representations from the current festival's goods. */
@Service
@Profile("db")
public class GoodsViewService {

    private static final ZoneId TIMEZONE = ZoneId.of("Asia/Seoul");
    private static final String CURRENCY = "KRW";

    private final GoodsStore store;
    private final FestivalProperties properties;
    private final OperationalAccountSettingsService accountSettings;
    private final ApiMetaSupport metaSupport;
    private final GoodsMediaUrlSupport mediaUrls;

    public GoodsViewService(
        GoodsStore store,
        FestivalProperties properties,
        OperationalAccountSettingsService accountSettings,
        ApiMetaSupport metaSupport,
        GoodsMediaUrlSupport mediaUrls
    ) {
        this.store = store;
        this.properties = properties;
        this.accountSettings = accountSettings;
        this.metaSupport = metaSupport;
        this.mediaUrls = mediaUrls;
    }

    public GoodsListSnapshot list(HttpServletRequest request) {
        String requestedLocale = ContentLocale.requestedLocale(request);
        List<Goods> goods = store.findAll(properties.configuredFestivalId());
        GoodsListResponse response = new GoodsListResponse(
            goods.stream().map(g -> toResponse(g, requestedLocale)).toList()
        );
        ApiMeta meta = metaSupport.unscopedMeta(request, requestedLocale);
        return new GoodsListSnapshot(response, meta);
    }

    public GoodsSnapshot find(HttpServletRequest request, UUID goodsId) {
        String requestedLocale = ContentLocale.requestedLocale(request);
        Goods goods = store.findById(properties.configuredFestivalId(), goodsId).orElseThrow(GoodsViewService::notFound);
        ApiMeta meta = metaSupport.unscopedMeta(request, requestedLocale);
        return new GoodsSnapshot(toResponse(goods, requestedLocale), meta);
    }

    public GoodsAvailabilityListSnapshot availabilityList(HttpServletRequest request) {
        String requestedLocale = ContentLocale.requestedLocale(request);
        List<Goods> goods = store.findAll(properties.configuredFestivalId());
        GoodsAvailabilityListResponse response = new GoodsAvailabilityListResponse(
            goods.stream().map(this::toAvailabilityResponse).toList()
        );
        ApiMeta meta = metaSupport.unscopedMeta(request, requestedLocale);
        return new GoodsAvailabilityListSnapshot(response, meta);
    }

    public GoodsAvailabilitySnapshot availability(HttpServletRequest request, UUID goodsId) {
        String requestedLocale = ContentLocale.requestedLocale(request);
        Goods goods = store.findById(properties.configuredFestivalId(), goodsId).orElseThrow(GoodsViewService::notFound);
        ApiMeta meta = metaSupport.unscopedMeta(request, requestedLocale);
        return new GoodsAvailabilitySnapshot(toAvailabilityResponse(goods), meta);
    }

    public GoodsPaymentGuideSnapshot paymentGuide(HttpServletRequest request, UUID goodsId) {
        String requestedLocale = ContentLocale.requestedLocale(request);
        Goods goods = store.findById(properties.configuredFestivalId(), goodsId).orElseThrow(GoodsViewService::notFound);
        String contentLocale = ContentLocale.resolve(goods.translations(), requestedLocale);
        GoodsTranslation translation = goods.translations().get(contentLocale);
        Optional<OperationalAccountSetting> setting = accountSettings.findCurrent(
            properties.configuredFestivalId(), OperationalAccountPurpose.GOODS
        );
        // transferLink stays null until the display-name source for the link
        // is decided, matching TicketGuideController's TICKET account guide.
        GoodsPaymentGuideResponse response = new GoodsPaymentGuideResponse(
            goods.id().toString(),
            translation.name(),
            new GoodsPaymentGuideResponse.Money(goods.priceAmount(), CURRENCY),
            account(setting),
            null,
            List.of(),
            null,
            null
        );
        ApiMeta meta = metaSupport.unscopedMeta(request, requestedLocale);
        return new GoodsPaymentGuideSnapshot(response, meta);
    }

    private GoodsResponse toResponse(Goods goods, String requestedLocale) {
        String contentLocale = ContentLocale.resolve(goods.translations(), requestedLocale);
        GoodsTranslation translation = goods.translations().get(contentLocale);
        List<GoodsColorResponse> colors = goods.colors().stream()
            .map(color -> new GoodsColorResponse(color.id().toString(), color.translations().get(contentLocale).name()))
            .toList();
        List<GoodsSizeResponse> sizes = goods.sizes().stream()
            .map(size -> new GoodsSizeResponse(size.id().toString(), size.translations().get(contentLocale).label()))
            .toList();
        List<GoodsImageResponse> images = goods.images().stream()
            .map(image -> toImageResponse(image, contentLocale))
            .toList();
        return new GoodsResponse(
            goods.id().toString(),
            contentLocale,
            translation.name(),
            translation.description(),
            new GoodsResponse.Money(goods.priceAmount(), CURRENCY),
            goods.optionMode().name(),
            images,
            colors,
            sizes
        );
    }

    private GoodsImageResponse toImageResponse(GoodsImage image, String contentLocale) {
        GoodsImageTranslation translation = image.translations().get(contentLocale);
        if (translation == null) {
            throw new GoodsDataConsistencyException("Goods image translation is missing for resolved content locale");
        }
        GoodsMediaUrlSupport.GoodsMediaUrls urls = mediaUrls.urls(image.mediaId());
        return new GoodsImageResponse(
            translation.alt(),
            urls.masterUrl(),
            urls.thumbnail320Url(),
            urls.thumbnail640Url()
        );
    }

    private GoodsAvailabilityResponse toAvailabilityResponse(Goods goods) {
        List<GoodsCombinationStatusResponse> combinations = goods.combinations().stream()
            .map(combo -> new GoodsCombinationStatusResponse(
                combo.id().toString(),
                combo.colorId() == null ? null : combo.colorId().toString(),
                combo.sizeId() == null ? null : combo.sizeId().toString(),
                combo.availability().name()
            ))
            .toList();
        boolean allSoldOut = !combinations.isEmpty()
            && combinations.stream().allMatch(c -> c.status().equals("SOLD_OUT"));
        Instant latestUpdate = goods.combinations().stream()
            .map(GoodsCombination::updatedAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
        return new GoodsAvailabilityResponse(
            goods.id().toString(),
            goods.translations().get("ko").name(),
            combinations,
            allSoldOut,
            latestUpdate == null ? null : OffsetDateTime.ofInstant(latestUpdate, TIMEZONE)
        );
    }

    private GoodsPaymentGuideResponse.BankAccount account(Optional<OperationalAccountSetting> setting) {
        return setting.filter(OperationalAccountSetting::isConfigured)
            .map(current -> new GoodsPaymentGuideResponse.BankAccount(
                current.bankName(), current.accountNumber(), current.accountHolder()
            ))
            .orElse(null);
    }

    private static ApiException notFound() {
        return new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 정보를 찾을 수 없습니다.", false);
    }

    public record GoodsListSnapshot(GoodsListResponse response, ApiMeta meta) {}

    public record GoodsSnapshot(GoodsResponse response, ApiMeta meta) {}

    public record GoodsAvailabilityListSnapshot(GoodsAvailabilityListResponse response, ApiMeta meta) {}

    public record GoodsAvailabilitySnapshot(GoodsAvailabilityResponse response, ApiMeta meta) {}

    public record GoodsPaymentGuideSnapshot(GoodsPaymentGuideResponse response, ApiMeta meta) {}
}
