package dev.espero.festival.web;

import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.TicketGuideConfig;
import dev.espero.festival.persistence.TicketGuideStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements GET /api/v2/ticket-guide per api-v2/contract-source.mjs
 * (TicketGuide schema). Only transfer-info display and a time-based status
 * are server-backed: no purchase, payment confirmation or wristband receipt
 * state exists anywhere (docs/wiki/product/ticket.md, TICKET-001).
 *
 * festival_start_date/festival_end_date are unconfirmed, so this currently
 * returns status=UNCONFIGURED in practice. Once 총학생회 confirms the dates,
 * updating the ticket_guide row is enough — no code change needed.
 *
 * Only Korean is publicly ready. Other locale and unknown query parameters are
 * rejected instead of silently falling back to Korean.
 */
@RestController
@RequestMapping("/api/v2")
@Profile("db")
public class TicketGuideController {

    private static final ZoneId TIMEZONE = ZoneId.of("Asia/Seoul");
    private static final String CONTENT_LOCALE = "ko";

    private final TicketGuideStore store;
    private final CatalogSnapshotProvider snapshots;
    private final ApiMetaSupport metaSupport;
    private final Clock clock;

    public TicketGuideController(
        TicketGuideStore store,
        CatalogSnapshotProvider snapshots,
        ApiMetaSupport metaSupport,
        Clock clock
    ) {
        this.store = store;
        this.snapshots = snapshots;
        this.metaSupport = metaSupport;
        this.clock = clock;
    }

    @GetMapping("/ticket-guide")
    public ApiResponse<TicketGuideResponse> getTicketGuide(HttpServletRequest request) {
        CatalogSnapshot snapshot = snapshots.required();
        metaSupport.setContext(request, snapshot.context(), CONTENT_LOCALE);
        validateQuery(request);
        Optional<TicketGuideConfig> config = store.find(snapshot.context().revisionId());
        LocalDate today = LocalDate.now(clock.withZone(TIMEZONE));

        TicketGuideResponse data = config.filter(TicketGuideConfig::hasSchedule)
            .map(guide -> scheduled(guide, today, snapshot.ticketMapTarget()))
            .orElseGet(() -> unconfigured(config, today));

        return new ApiResponse<>(data, metaSupport.meta(request, snapshot.context(), CONTENT_LOCALE));
    }

    private TicketGuideResponse scheduled(
        TicketGuideConfig guide,
        LocalDate today,
        CatalogSnapshot.MapTarget ticketMapTarget
    ) {
        LocalDate scheduleDate = clamp(today, guide.festivalStartDate(), guide.festivalEndDate());
        LocalTime now = LocalTime.now(clock.withZone(TIMEZONE));

        TicketGuideResponse.Status status;
        if (today.isBefore(guide.festivalStartDate())) {
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
            open ? account(guide) : null,
            open ? transferLink(guide) : null,
            mapTarget(ticketMapTarget),
            guide.instructions()
        );
    }

    private TicketGuideResponse unconfigured(Optional<TicketGuideConfig> config, LocalDate today) {
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
            null,
            config.map(TicketGuideConfig::instructions).orElse(List.of())
        );
    }

    private TicketGuideResponse.Money money(TicketGuideConfig guide) {
        return guide.unitPriceAmount() == null
            ? null
            : new TicketGuideResponse.Money(guide.unitPriceAmount(), "KRW");
    }

    private TicketGuideResponse.BankAccount account(TicketGuideConfig guide) {
        return guide.hasAccount()
            ? new TicketGuideResponse.BankAccount(guide.accountBankName(), guide.accountNumber(), guide.accountHolder())
            : null;
    }

    private TicketGuideResponse.Link transferLink(TicketGuideConfig guide) {
        return guide.hasTransferLink()
            ? new TicketGuideResponse.Link(guide.transferLinkLabel(), guide.transferLinkUrl(), "_blank")
            : null;
    }

    private TicketGuideResponse.MapTarget mapTarget(CatalogSnapshot.MapTarget target) {
        return target == null
            ? null
            : new TicketGuideResponse.MapTarget(target.mapId(), target.placeId(), target.pinId(), target.mapVersion());
    }

    private void validateQuery(HttpServletRequest request) {
        for (java.util.Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!entry.getKey().equals("locale") || entry.getValue().length != 1
                || !CONTENT_LOCALE.equals(entry.getValue()[0])) {
                throw new ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_QUERY",
                    "요청 파라미터를 확인해 주세요.",
                    false
                );
            }
        }
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
