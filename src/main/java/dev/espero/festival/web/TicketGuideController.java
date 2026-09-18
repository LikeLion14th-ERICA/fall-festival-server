package dev.espero.festival.web;

import dev.espero.festival.account.OperationalAccountPurpose;
import dev.espero.festival.account.OperationalAccountSetting;
import dev.espero.festival.account.OperationalAccountSettingsService;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.TicketGuideConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements GET /api/v2/ticket-guide per api-v2/contract-source.mjs
 * (TicketGuide schema). Only transfer-info display and a time-based status
 * are server-backed: no purchase, payment confirmation or wristband receipt
 * state exists anywhere (docs/wiki/product/ticket.md, TICKET-001).
 *
 * <p>The static price, schedule, instructions and map target come from the
 * published catalog revision. The bank account comes from the revision
 * independent {@code TICKET} operational setting, so changing an account never
 * requires a catalog revision and never appears in a catalog rollback.</p>
 *
 * <p>The response is conditional: clients poll it and revalidate with
 * If-None-Match. The ETag covers the served representation including the
 * payment settings version, so an account change is visible on the next poll.
 * {@code transferLink} stays null until the display-name source for the link
 * is decided (docs/wiki/product/decisions.md).</p>
 *
 * <p>Only Korean is publicly ready. Other locale and unknown query parameters are
 * rejected instead of silently falling back to Korean.</p>
 */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class TicketGuideController {

    private static final ZoneId TIMEZONE = ZoneId.of("Asia/Seoul");
    private static final String CONTENT_LOCALE = PublicContentLocale.KOREAN;
    private static final String CACHE_CONTROL = "private, no-cache";

    private final CatalogSnapshotProvider snapshots;
    private final OperationalAccountSettingsService accountSettings;
    private final ConditionalResponseSupport conditionalResponses;
    private final ApiMetaSupport metaSupport;
    private final FestivalProperties festivalProperties;
    private final Clock clock;

    public TicketGuideController(
        CatalogSnapshotProvider snapshots,
        OperationalAccountSettingsService accountSettings,
        ConditionalResponseSupport conditionalResponses,
        ApiMetaSupport metaSupport,
        FestivalProperties festivalProperties,
        Clock clock
    ) {
        this.snapshots = snapshots;
        this.accountSettings = accountSettings;
        this.conditionalResponses = conditionalResponses;
        this.metaSupport = metaSupport;
        this.festivalProperties = festivalProperties;
        this.clock = clock;
    }

    @GetMapping("/ticket-guide")
    public ResponseEntity<ConditionalApiResponse<TicketGuideResponse>> getTicketGuide(
        HttpServletRequest request
    ) {
        CatalogSnapshot snapshot = snapshots.required();
        metaSupport.setContext(request, snapshot.context(), CONTENT_LOCALE);
        validateQuery(request);

        Optional<TicketGuideConfig> config = Optional.ofNullable(snapshot.ticketGuideConfig());
        // The configured festival UUID is the identity this process serves.
        // The snapshot's public festivalId is an API id string and is not
        // parsed back into a UUID here.
        Optional<OperationalAccountSetting> setting = accountSettings.findCurrent(
            festivalProperties.configuredFestivalId(),
            OperationalAccountPurpose.TICKET
        );
        LocalDate today = LocalDate.now(clock.withZone(TIMEZONE));

        TicketGuideResponse data = config.filter(TicketGuideConfig::hasSchedule)
            .map(guide -> scheduled(guide, setting, today, snapshot.ticketMapTarget()))
            .orElseGet(() -> unconfigured(config, setting, today, snapshot.ticketMapTarget()));

        return conditionalResponses.respond(
            request,
            data,
            metaSupport.meta(request, snapshot.context(), CONTENT_LOCALE),
            CACHE_CONTROL
        );
    }

    private TicketGuideResponse scheduled(
        TicketGuideConfig guide,
        Optional<OperationalAccountSetting> setting,
        LocalDate today,
        CatalogSnapshot.MapTarget ticketMapTarget
    ) {
        LocalDate scheduleDate = clamp(today, guide.festivalStartDate(), guide.festivalEndDate());
        LocalTime now = LocalTime.now(clock.withZone(TIMEZONE));
        boolean accountConfigured = setting.filter(OperationalAccountSetting::isConfigured).isPresent();

        TicketGuideResponse.Status status;
        if (!accountConfigured) {
            // Transfers cannot be completed without an approved account, so the
            // schedule alone is not a usable transfer state.
            status = TicketGuideResponse.Status.UNCONFIGURED;
        } else if (today.isBefore(guide.festivalStartDate())) {
            status = TicketGuideResponse.Status.BEFORE_FESTIVAL;
        } else if (today.isAfter(guide.festivalEndDate())) {
            status = TicketGuideResponse.Status.FESTIVAL_ENDED;
        } else if (!now.isBefore(guide.dailyTransferCloseTime())) {
            status = TicketGuideResponse.Status.DAILY_CLOSED;
        } else {
            status = TicketGuideResponse.Status.TRANSFER_OPEN;
        }

        boolean open = status == TicketGuideResponse.Status.TRANSFER_OPEN;

        return new TicketGuideResponse(
            today,
            status,
            money(guide),
            atSeoul(scheduleDate, guide.dailyTransferOpenTime()),
            atSeoul(scheduleDate, guide.dailyTransferCloseTime()),
            atSeoul(scheduleDate, guide.dailyPickupOpenTime()),
            atSeoul(scheduleDate, guide.dailyPickupCloseTime()),
            open ? account(setting) : null,
            null,
            paymentSettingsVersion(setting),
            mapTarget(ticketMapTarget),
            guide.instructions()
        );
    }

    private TicketGuideResponse unconfigured(
        Optional<TicketGuideConfig> config,
        Optional<OperationalAccountSetting> setting,
        LocalDate today,
        CatalogSnapshot.MapTarget ticketMapTarget
    ) {
        return new TicketGuideResponse(
            today,
            TicketGuideResponse.Status.UNCONFIGURED,
            config.map(this::money).orElse(null),
            null,
            null,
            null,
            null,
            null,
            null,
            paymentSettingsVersion(setting),
            mapTarget(ticketMapTarget),
            config.map(TicketGuideConfig::instructions).orElse(List.of())
        );
    }

    private TicketGuideResponse.Money money(TicketGuideConfig guide) {
        return guide.unitPriceAmount() == null
            ? null
            : new TicketGuideResponse.Money(guide.unitPriceAmount(), "KRW");
    }

    private TicketGuideResponse.BankAccount account(Optional<OperationalAccountSetting> setting) {
        return setting.filter(OperationalAccountSetting::isConfigured)
            .map(current -> new TicketGuideResponse.BankAccount(
                current.bankName(), current.accountNumber(), current.accountHolder()
            ))
            .orElse(null);
    }

    /**
     * The version of the current setting row, including a cleared one, so a
     * client can tell a new account apart from an unchanged one. A festival
     * whose account was never configured has no row and therefore no version.
     */
    private Long paymentSettingsVersion(Optional<OperationalAccountSetting> setting) {
        return setting.map(OperationalAccountSetting::version).orElse(null);
    }

    private TicketGuideResponse.MapTarget mapTarget(CatalogSnapshot.MapTarget target) {
        return target == null
            ? null
            : new TicketGuideResponse.MapTarget(target.mapId(), target.placeId(), target.pinId(), target.mapVersion());
    }

    private void validateQuery(HttpServletRequest request) {
        for (java.util.Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1) {
                throw PublicContentLocale.invalidQuery();
            }
        }
        PublicContentLocale.requirePublishedLocale(request);
    }

    private OffsetDateTime atSeoul(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TIMEZONE).toOffsetDateTime();
    }

    private LocalDate clamp(LocalDate date, LocalDate start, LocalDate end) {
        if (date.isBefore(start)) {
            return start;
        }
        if (date.isAfter(end)) {
            return end;
        }
        return date;
    }
}
