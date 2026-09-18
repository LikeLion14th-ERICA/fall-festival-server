package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.espero.festival.account.OperationalAccountPurpose;
import dev.espero.festival.account.OperationalAccountSetting;
import dev.espero.festival.account.OperationalAccountSettingsService;
import dev.espero.festival.account.OperationalAccountState;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.domain.TicketGuideConfig;
import dev.espero.festival.support.ApiMetaTestFixtures;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

class TicketGuideControllerTest {

    private static final String CACHE_CONTROL = "private, no-cache";

    private final CatalogSnapshotProvider snapshots = mock(CatalogSnapshotProvider.class);
    private final OperationalAccountSettingsService accountSettings =
        mock(OperationalAccountSettingsService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private TicketGuideController controllerAt(String instant, TicketGuideConfig config) {
        return controllerAt(instant, config, null, configuredAccount(1));
    }

    private TicketGuideController controllerAt(
        String instant,
        TicketGuideConfig config,
        CatalogSnapshot.MapTarget ticketMapTarget,
        Optional<OperationalAccountSetting> setting
    ) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        when(request.getParameterMap()).thenReturn(Map.of());
        when(snapshots.required()).thenReturn(snapshot(config, ticketMapTarget));
        when(accountSettings.findCurrent(ApiMetaTestFixtures.FESTIVAL_ID, OperationalAccountPurpose.TICKET))
            .thenReturn(setting);
        return new TicketGuideController(
            snapshots,
            accountSettings,
            new ConditionalResponseSupport(new ObjectMapper()),
            ApiMetaTestFixtures.contentMetaSupport(clock),
            new FestivalProperties(ApiMetaTestFixtures.FESTIVAL_ID.toString()),
            clock
        );
    }

    private CatalogSnapshot snapshot(TicketGuideConfig config, CatalogSnapshot.MapTarget ticketMapTarget) {
        return new CatalogSnapshot(
            new CatalogSnapshot.FestivalContext(
                ApiMetaTestFixtures.FESTIVAL_ID.toString(),
                ApiMetaTestFixtures.REVISION_ID,
                7
            ),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            config,
            ticketMapTarget
        );
    }

    private TicketGuideConfig scheduledConfig() {
        return new TicketGuideConfig(
            15000,
            List.of("안내1", "안내2"),
            LocalDate.parse("2030-10-01"),
            LocalDate.parse("2030-10-03"),
            LocalTime.parse("00:00"),
            LocalTime.parse("21:00"),
            LocalTime.parse("13:00"),
            LocalTime.parse("21:00"),
            Instant.parse("2030-09-01T00:00:00Z")
        );
    }

    private Optional<OperationalAccountSetting> configuredAccount(long version) {
        return configuredAccount(version, "MOCK-NOT-PAYABLE");
    }

    private Optional<OperationalAccountSetting> configuredAccount(long version, String accountNumber) {
        return Optional.of(new OperationalAccountSetting(
            ApiMetaTestFixtures.FESTIVAL_ID,
            OperationalAccountPurpose.TICKET,
            OperationalAccountState.CONFIGURED,
            version,
            "개발용 은행",
            accountNumber,
            "개발용 예금주",
            null,
            Instant.parse("2030-09-01T00:00:00Z")
        ));
    }

    private Optional<OperationalAccountSetting> clearedAccount(long version) {
        return Optional.of(new OperationalAccountSetting(
            ApiMetaTestFixtures.FESTIVAL_ID,
            OperationalAccountPurpose.TICKET,
            OperationalAccountState.UNCONFIGURED,
            version,
            null,
            null,
            null,
            null,
            Instant.parse("2030-09-02T00:00:00Z")
        ));
    }

    private TicketGuideResponse body(ResponseEntity<ConditionalApiResponse<TicketGuideResponse>> response) {
        return response.getBody().data();
    }

    @Test
    void returnsUnconfiguredWhenTheSnapshotHasNoTicketGuide() {
        ResponseEntity<ConditionalApiResponse<TicketGuideResponse>> response =
            controllerAt("2030-10-01T09:00:00Z", null).getTicketGuide(request);
        TicketGuideResponse data = body(response);

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.UNCONFIGURED);
        assertThat(data.unitPrice()).isNull();
        assertThat(data.account()).isNull();
        assertThat(data.mapTarget()).isNull();
        assertThat(data.instructions()).isEmpty();
        assertThat(response.getBody().meta().festivalId())
            .isEqualTo(ApiMetaTestFixtures.FESTIVAL_ID.toString());
        assertThat(response.getBody().meta().revision()).isEqualTo(7);
    }

    @Test
    void returnsUnconfiguredWithKnownPriceWhenDatesAreMissing() {
        TicketGuideConfig partial = new TicketGuideConfig(
            15000,
            List.of("안내1"), null, null,
            LocalTime.parse("00:00"), LocalTime.parse("21:00"),
            LocalTime.parse("13:00"), LocalTime.parse("21:00"),
            Instant.parse("2030-09-01T00:00:00Z")
        );

        CatalogSnapshot.MapTarget target = new CatalogSnapshot.MapTarget(
            "map-overview", "place-ticket-zone", "pin-ticket-zone", "asset-2026-01"
        );
        TicketGuideResponse data = body(
            controllerAt("2030-10-01T09:00:00Z", partial, target, configuredAccount(1))
                .getTicketGuide(request)
        );

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.UNCONFIGURED);
        assertThat(data.unitPrice().amount()).isEqualTo(15000);
        assertThat(data.instructions()).containsExactly("안내1");
        assertThat(data.mapTarget()).isEqualTo(new TicketGuideResponse.MapTarget(
            "map-overview", "place-ticket-zone", "pin-ticket-zone", "asset-2026-01"
        ));
    }

    @Test
    void returnsUnconfiguredWhileTheAccountSettingIsMissingOrCleared() {
        TicketGuideResponse never = body(
            controllerAt("2030-10-01T09:00:00Z", scheduledConfig(), null, Optional.empty())
                .getTicketGuide(request)
        );

        assertThat(never.status()).isEqualTo(TicketGuideResponse.Status.UNCONFIGURED);
        assertThat(never.account()).isNull();
        assertThat(never.paymentSettingsVersion()).isNull();
        assertThat(never.transferOpensAt()).isNotNull();

        TicketGuideResponse cleared = body(
            controllerAt("2030-10-01T09:00:00Z", scheduledConfig(), null, clearedAccount(4))
                .getTicketGuide(request)
        );

        assertThat(cleared.status()).isEqualTo(TicketGuideResponse.Status.UNCONFIGURED);
        assertThat(cleared.account()).isNull();
        assertThat(cleared.paymentSettingsVersion()).isEqualTo(4);
    }

    @Test
    void returnsBeforeFestivalAheadOfTheFirstDay() {
        TicketGuideResponse data = body(
            controllerAt("2030-09-30T09:00:00Z", scheduledConfig()).getTicketGuide(request)
        );

        assertThat(data.date()).isEqualTo(LocalDate.parse("2030-09-30"));
        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.BEFORE_FESTIVAL);
        assertThat(data.account()).isNull();
        assertThat(data.transferOpensAt().toLocalDate()).isEqualTo(LocalDate.parse("2030-10-01"));
    }

    @Test
    void returnsTransferOpenDuringTheDayBeforeCloseTime() {
        TicketGuideResponse data = body(
            controllerAt("2030-10-01T09:00:00Z", scheduledConfig()).getTicketGuide(request)
        );

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.TRANSFER_OPEN);
        assertThat(data.account()).isNotNull();
        assertThat(data.account().bankName()).isEqualTo("개발용 은행");
        assertThat(data.transferLink()).isNull();
        assertThat(data.paymentSettingsVersion()).isEqualTo(1);
    }

    @Test
    void returnsDailyClosedAtOrAfterCloseTimeAndHidesAccount() {
        TicketGuideResponse data = body(
            controllerAt("2030-10-01T12:00:00Z", scheduledConfig()).getTicketGuide(request)
        );

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.DAILY_CLOSED);
        assertThat(data.account()).isNull();
        assertThat(data.paymentSettingsVersion()).isEqualTo(1);
    }

    @Test
    void returnsFestivalEndedAfterTheLastDayAndClampsScheduleDate() {
        TicketGuideResponse data = body(
            controllerAt("2030-10-05T09:00:00Z", scheduledConfig()).getTicketGuide(request)
        );

        assertThat(data.status()).isEqualTo(TicketGuideResponse.Status.FESTIVAL_ENDED);
        assertThat(data.account()).isNull();
        assertThat(data.transferOpensAt().toLocalDate()).isEqualTo(LocalDate.parse("2030-10-03"));
    }

    @Test
    void sendsAStrongEtagAndPrivateNoCacheOnEveryRepresentation() {
        ResponseEntity<ConditionalApiResponse<TicketGuideResponse>> response =
            controllerAt("2030-10-01T09:00:00Z", scheduledConfig()).getTicketGuide(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo(CACHE_CONTROL);
        assertThat(response.getHeaders().getFirst(ConditionalResponseSupport.ETAG_HEADER))
            .matches("\"[0-9a-f]{64}\"");
        assertThat(response.getHeaders().getFirst(ConditionalResponseSupport.SERVER_TIME_HEADER)).isNotBlank();
    }

    @Test
    void returnsNotModifiedForAMatchingEtagAndKeepsTheCacheDirectives() {
        String etag = controllerAt("2030-10-01T09:00:00Z", scheduledConfig())
            .getTicketGuide(request)
            .getHeaders()
            .getFirst(ConditionalResponseSupport.ETAG_HEADER);

        HttpServletRequest revalidation = mock(HttpServletRequest.class);
        when(revalidation.getParameterMap()).thenReturn(Map.of());
        when(revalidation.getHeader(ConditionalResponseSupport.IF_NONE_MATCH_HEADER)).thenReturn(etag);

        ResponseEntity<ConditionalApiResponse<TicketGuideResponse>> response =
            controllerAt("2030-10-01T09:00:00Z", scheduledConfig()).getTicketGuide(revalidation);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst(ConditionalResponseSupport.ETAG_HEADER)).isEqualTo(etag);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo(CACHE_CONTROL);
    }

    @Test
    void changesTheEtagAtTheTransferBoundaryAndAfterAnAccountChange() {
        String open = etagAt("2030-10-01T09:00:00Z", configuredAccount(1));
        String closed = etagAt("2030-10-01T12:00:00Z", configuredAccount(1));
        String reconfigured = etagAt("2030-10-01T09:00:00Z", configuredAccount(2, "MOCK-NOT-PAYABLE-2"));
        String clearedSetting = etagAt("2030-10-01T09:00:00Z", clearedAccount(3));

        assertThat(open).isNotEqualTo(closed);
        assertThat(open).isNotEqualTo(reconfigured);
        assertThat(reconfigured).isNotEqualTo(clearedSetting);
    }

    @Test
    void keepsTheEtagStableWhileNothingAboutTheRepresentationChanges() {
        assertThat(etagAt("2030-10-01T09:00:00Z", configuredAccount(1)))
            .isEqualTo(etagAt("2030-10-01T09:30:00Z", configuredAccount(1)));
    }

    private String etagAt(String instant, Optional<OperationalAccountSetting> setting) {
        HttpServletRequest poll = mock(HttpServletRequest.class);
        when(poll.getParameterMap()).thenReturn(Map.of());
        return controllerAt(instant, scheduledConfig(), null, setting)
            .getTicketGuide(poll)
            .getHeaders()
            .getFirst(ConditionalResponseSupport.ETAG_HEADER);
    }

    @Test
    void distinguishesAnUnreadyKnownLocaleFromAnUnknownLocale() {
        TicketGuideController controller = controllerAt("2030-10-01T09:00:00Z", scheduledConfig());
        HttpServletRequest knownButUnready = mock(HttpServletRequest.class);
        HttpServletRequest unknown = mock(HttpServletRequest.class);
        when(knownButUnready.getParameterMap()).thenReturn(Map.of("locale", new String[] {"en"}));
        when(unknown.getParameterMap()).thenReturn(Map.of("locale", new String[] {"xx"}));
        when(knownButUnready.getParameter("locale")).thenReturn("en");
        when(unknown.getParameter("locale")).thenReturn("xx");

        assertThatThrownBy(() -> controller.getTicketGuide(knownButUnready))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("LOCALE_NOT_READY"));
        assertThatThrownBy(() -> controller.getTicketGuide(unknown))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code()).isEqualTo("INVALID_QUERY"));
    }
}
