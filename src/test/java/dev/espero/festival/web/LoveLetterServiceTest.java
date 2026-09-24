package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.persistence.LoveLetterStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LoveLetterServiceTest {
    private static final UUID FESTIVAL = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID PARTICIPANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TOKEN = "test-anonymous-participant-token-1234567890";
    private static final LocalDate DAY = LocalDate.parse("2030-10-01");

    @Test
    void registrationAssignsBeforeStartingRevealWait() throws Exception {
        Fixture fixture = fixture();
        when(fixture.store.request(eq(PARTICIPANT), eq("retry-key"), any())).thenReturn(Optional.empty());
        when(fixture.store.randomCandidate(eq(FESTIVAL), eq(PARTICIPANT), eq("FEMALE"), eq(DAY)))
            .thenReturn(Optional.of(candidate()));
        assertThat(fixture.service.register(TOKEN, input(), "retry-key").state()).isEqualTo("WAITING");
        verify(fixture.store).createPendingDay(eq(PARTICIPANT), eq(DAY), any());
        verify(fixture.store).insertExchange(eq(FESTIVAL), eq(PARTICIPANT), eq(candidate().id()), eq(DAY), any());
        verify(fixture.store).completeDay(eq(PARTICIPANT), eq(DAY), any(), any());
    }

    @Test
    void kstParticipationWindowHonorsEveryExactBoundary() throws Exception {
        Fixture beforeOpen = fixture(Instant.parse("2030-10-01T23:59:59Z")); // Oct 2, 08:59:59 KST
        assertThat(beforeOpen.service.status(TOKEN).state()).isEqualTo("BEFORE_OPEN");
        assertThat(beforeOpen.service.status(TOKEN).nextParticipationAt())
            .isEqualTo(Instant.parse("2030-10-02T00:00:00Z"));

        Fixture opening = fixture(Instant.parse("2030-10-02T00:00:00Z")); // Oct 2, 09:00:00 KST
        assertThat(opening.service.status(TOKEN).state()).isEqualTo("WRITABLE");

        Fixture beforeDailyClose = fixture(Instant.parse("2030-10-02T14:59:59Z")); // Oct 2, 23:59:59 KST
        assertThat(beforeDailyClose.service.status(TOKEN).state()).isEqualTo("WRITABLE");

        Fixture midnight = fixture(Instant.parse("2030-10-02T15:00:00Z")); // Oct 3, 00:00:00 KST
        assertThat(midnight.service.status(TOKEN).state()).isEqualTo("BEFORE_OPEN");
        assertThat(midnight.service.status(TOKEN).nextParticipationAt())
            .isEqualTo(Instant.parse("2030-10-03T00:00:00Z"));

        Fixture festivalClose = fixture(Instant.parse("2030-10-03T15:00:00Z")); // Oct 4, 00:00:00 KST
        assertThat(festivalClose.service.status(TOKEN).state()).isEqualTo("CLOSED");
    }

    @Test
    void openingIsRejectedAtFiftyNineSecondsAndAllowedAtExactlySixty() throws Exception {
        Instant assignedAt = Instant.parse("2030-10-01T03:00:00Z");
        var result = exchange(assignedAt);
        LoveLetterCrypto crypto = crypto();
        var assignedLetter = new LoveLetterStore.Letter(candidate().id(), UUID.randomUUID(), "FEMALE",
            crypto.encrypt("받는 이름"), crypto.encrypt("받는 내용"), crypto.encrypt("받는 연락처"),
            crypto.version(), false);

        Fixture waiting = fixture(assignedAt.plusSeconds(59));
        when(waiting.store.exchange(PARTICIPANT, result.id())).thenReturn(Optional.of(result));
        when(waiting.store.latestExchange(PARTICIPANT)).thenReturn(Optional.of(result));
        assertThatThrownBy(() -> waiting.service.open(TOKEN, result.id()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("LOVE_WAITING");
        verify(waiting.store, never()).markOpened(any(), any());
        verify(waiting.store, never()).letter(any());

        Fixture ready = fixture(assignedAt.plusSeconds(60));
        when(ready.store.exchange(PARTICIPANT, result.id())).thenReturn(Optional.of(result));
        when(ready.store.latestExchange(PARTICIPANT)).thenReturn(Optional.of(result));
        when(ready.store.letter(result.letterId())).thenReturn(Optional.of(assignedLetter));
        assertThat(ready.service.open(TOKEN, result.id()))
            .isEqualTo(new LoveLetterService.Opened(result.id(), "받는 이름", "받는 내용", "받는 연락처"));
        verify(ready.store).markOpened(eq(result.id()), eq(assignedAt.plusSeconds(60)));
    }

    @Test
    void registrationValidatesUnicodeLengthConsentAndRequiredConfirmations() throws Exception {
        Fixture fixture = fixture();
        when(fixture.store.randomCandidate(FESTIVAL, PARTICIPANT, "FEMALE", DAY))
            .thenReturn(Optional.of(candidate()));

        var atLimits = new LoveLetterService.Input("MALE", "😀".repeat(20), "가".repeat(100), "🙂".repeat(100),
            true, true, "v1");
        assertThat(fixture.service.register(TOKEN, atLimits, "valid-limits").state()).isEqualTo("WAITING");

        List<LoveLetterService.Input> invalid = List.of(
            new LoveLetterService.Input("OTHER", "별명", "한 줄", "연락처", true, true, "v1"),
            new LoveLetterService.Input("MALE", "😀".repeat(21), "한 줄", "연락처", true, true, "v1"),
            new LoveLetterService.Input("MALE", "별명", "가".repeat(101), "연락처", true, true, "v1"),
            new LoveLetterService.Input("MALE", "별명", "첫 줄\n둘째 줄", "연락처", true, true, "v1"),
            new LoveLetterService.Input("MALE", "별명", "한 줄", "🙂".repeat(101), true, true, "v1"),
            new LoveLetterService.Input("MALE", "별명", "한 줄", "연락처", false, true, "v1"),
            new LoveLetterService.Input("MALE", "별명", "한 줄", "연락처", true, false, "v1"),
            new LoveLetterService.Input("MALE", "별명", "한 줄", "연락처", true, true, "old-consent"));
        for (int index = 0; index < invalid.size(); index++) {
            var invalidInput = invalid.get(index);
            String key = "invalid-" + index;
            assertThatThrownBy(() -> fixture.service.register(TOKEN, invalidInput, key))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("LOVE_INVALID_INPUT");
        }
        verify(fixture.store, times(1)).insertLetter(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void configurationRequiresExactKstFestivalHoursAndRejectsInvalidSettingsWithoutWrites() throws Exception {
        Fixture fixture = fixture();
        Instant opensAt = Instant.parse("2030-09-30T00:00:00Z"); // Oct 1, 09:00 KST
        Instant closesAt = Instant.parse("2030-10-03T15:00:00Z"); // Oct 4, 00:00 KST

        fixture.service.configure(opensAt, closesAt, "v1", "configure-valid");
        verify(fixture.store).configure(FESTIVAL, opensAt, closesAt, "v1");

        var invalid = List.of(
            new Configuration(null, closesAt, "v1"),
            new Configuration(opensAt, null, "v1"),
            new Configuration(Instant.parse("2030-09-29T23:59:59Z"), closesAt, "v1"),
            new Configuration(Instant.parse("2030-09-30T00:00:01Z"), closesAt, "v1"),
            new Configuration(opensAt, Instant.parse("2030-10-03T14:59:59Z"), "v1"),
            new Configuration(opensAt, Instant.parse("2030-10-03T15:00:01Z"), "v1"),
            new Configuration(closesAt, opensAt, "v1"),
            new Configuration(opensAt, closesAt, null),
            new Configuration(opensAt, closesAt, "bad version"));
        for (Configuration configuration : invalid) {
            assertThatThrownBy(() -> fixture.service.configure(configuration.opensAt(), configuration.closesAt(),
                configuration.consentVersion(), "configure-invalid"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("LOVE_INVALID_INPUT");
        }
        verify(fixture.store, times(1)).configure(FESTIVAL, opensAt, closesAt, "v1");
        verify(fixture.audit).record(AdminAuditAction.LOVE_CONFIGURED, AdminAuditResourceType.LOVE_SETTINGS,
            FESTIVAL.toString(), "configure-valid");
    }

    @Test
    void seedLocksBeforeReadingConsentSettingsAndWritesNothingWhenConsentIsStale() throws Exception {
        Fixture fixture = fixture();
        InOrder lockThenSettings = inOrder(fixture.store);
        var staleConsent = new LoveLetterService.SeedInput(DAY,
            new LoveLetterService.Input("FEMALE", "사전 별명", "사전 한 줄", "@seed-contact", true, true, "old-consent"),
            Instant.parse("2030-09-30T00:00:00Z"));

        assertThatThrownBy(() -> fixture.service.seed(staleConsent, "stale-seed-consent"))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("LOVE_INVALID_INPUT");

        lockThenSettings.verify(fixture.store).lockFestival(FESTIVAL);
        lockThenSettings.verify(fixture.store).settings(FESTIVAL);
        verify(fixture.store, never()).seedParticipant(any(), any());
        verify(fixture.store, never()).insertLetter(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.store, never()).seedDay(any(), any(), any());
        verify(fixture.store, never()).invite(any(), any(), any(), any());
        verify(fixture.audit, never()).record(any(), any(), any(), any());
    }

    @Test
    void sameIdempotencyKeyRecoversOriginalRegistration() throws Exception {
        Fixture fixture = fixture();
        UUID letter = UUID.fromString("22222222-2222-4222-8222-222222222222");
        when(fixture.store.request(eq(PARTICIPANT), eq("retry-key"), any()))
            .thenReturn(Optional.of(new LoveLetterStore.RequestResult(letter, null)));
        when(fixture.store.letterCreatedAt(letter)).thenReturn(Optional.of(Instant.parse("2030-10-01T03:00:00Z")));

        assertThat(fixture.service.register(TOKEN, input(), "retry-key"))
            .isEqualTo(new LoveLetterService.Registered(letter, Instant.parse("2030-10-01T03:01:00Z"), "WAITING"));
        verify(fixture.store, never()).randomCandidate(any(), any(), any(), any());
    }

    @Test
    void emptyPoolDoesNotCreateDayOrLetter() throws Exception {
        Fixture fixture = fixture();
        when(fixture.store.randomCandidate(FESTIVAL, PARTICIPANT, "FEMALE", DAY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> fixture.service.register(TOKEN, input(), "retry-key"))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("LOVE_POOL_EMPTY");
        verify(fixture.store, never()).createPendingDay(any(), any(), any());
        verify(fixture.store, never()).insertLetter(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.store, never()).insertExchange(any(), any(), any(), any(), any());
        verify(fixture.store, never()).completeDay(any(), any(), any(), any());
        verify(fixture.store, never()).saveRequest(any(), any(), any(), any(), any(), any());
    }

    @Test
    void statusRevealsPreassignedExchangeAtExactlySixtySeconds() throws Exception {
        UUID letter = UUID.fromString("33333333-3333-4333-8333-333333333333");
        Instant written = Instant.parse("2030-10-01T03:00:00Z");
        Fixture waiting = fixture(written.plusSeconds(59));
        when(waiting.store.dayLetter(PARTICIPANT, DAY)).thenReturn(Optional.of(
            new LoveLetterStore.DayLetter(letter, "MALE", "COMPLETED", written)));
        when(waiting.store.latestExchange(PARTICIPANT)).thenReturn(Optional.of(exchange(written)));
        assertThat(waiting.service.status(TOKEN).state()).isEqualTo("WAITING");
        assertThat(waiting.service.status(TOKEN).exchangeId()).isNull();
        Fixture ready = fixture(written.plusSeconds(60));
        when(ready.store.dayLetter(PARTICIPANT, DAY)).thenReturn(Optional.of(
            new LoveLetterStore.DayLetter(letter, "MALE", "COMPLETED", written)));
        when(ready.store.latestExchange(PARTICIPANT)).thenReturn(Optional.of(exchange(written)));
        assertThat(ready.service.status(TOKEN).state()).isEqualTo("SEALED");
        assertThat(ready.service.status(TOKEN).exchangeId()).isEqualTo(exchange(written).id());
    }

    @Test
    void overnightStatusKeepsMidnightAssignmentHiddenUntilDailyOpening() throws Exception {
        Instant written = Instant.parse("2030-10-01T14:59:30Z"); // 23:59:30 KST
        Fixture midnight = fixture(Instant.parse("2030-10-01T15:00:15Z")); // 00:00:15 KST
        when(midnight.store.latestExchange(PARTICIPANT)).thenReturn(Optional.of(exchange(written)));
        var hidden = midnight.service.status(TOKEN);
        assertThat(hidden.state()).isEqualTo("BEFORE_OPEN");
        assertThat(hidden.exchangeId()).isNull();
        assertThat(hidden.revealAt()).isNull();
        assertThat(hidden.nextParticipationAt()).isEqualTo(Instant.parse("2030-10-02T00:00:00Z"));

        Fixture opening = fixture(Instant.parse("2030-10-02T00:00:00Z")); // 09:00 KST
        when(opening.store.latestExchange(PARTICIPANT)).thenReturn(Optional.of(exchange(written)));
        assertThat(opening.service.status(TOKEN).state()).isEqualTo("SEALED");
        assertThat(opening.service.status(TOKEN).exchangeId()).isEqualTo(exchange(written).id());
    }

    @Test
    void sameIdempotencyKeyRecoversRegistrationAfterClosingTime() throws Exception {
        Fixture fixture = fixture(Instant.parse("2030-10-03T16:00:00Z"));
        UUID letter = UUID.fromString("66666666-6666-4666-8666-666666666666");
        Instant createdAt = Instant.parse("2030-10-03T14:59:00Z");
        when(fixture.store.request(eq(PARTICIPANT), eq("retry-key"), any()))
            .thenReturn(Optional.of(new LoveLetterStore.RequestResult(letter, null)));
        when(fixture.store.letterCreatedAt(letter)).thenReturn(Optional.of(createdAt));

        assertThat(fixture.service.register(TOKEN, input(), "retry-key"))
            .isEqualTo(new LoveLetterService.Registered(letter, createdAt.plusSeconds(60), "WAITING"));
        verify(fixture.store, never()).randomCandidate(any(), any(), any(), any());
    }

    @Test
    void personalFieldsAreAuthenticatedCiphertext() {
        LoveLetterCrypto crypto = crypto();
        String encoded = crypto.encrypt("@private-contact");
        assertThat(encoded).doesNotContain("@private-contact");
        assertThat(crypto.decrypt(encoded, "v1")).isEqualTo("@private-contact");
        assertThatThrownBy(() -> crypto.decrypt(encoded.substring(0, encoded.length() - 2) + "AA", "v1"))
            .isInstanceOf(IllegalStateException.class);
    }

    private Fixture fixture() throws Exception {
        return fixture(Instant.parse("2030-10-01T03:00:00Z"));
    }

    private Fixture fixture(Instant instant) throws Exception {
        LoveLetterStore store = mock(LoveLetterStore.class);
        Clock clock = Clock.fixed(instant, ZoneId.of("Asia/Seoul"));
        when(store.settings(FESTIVAL)).thenReturn(Optional.of(new LoveLetterStore.Settings(
            Instant.parse("2030-10-01T00:00:00Z"), Instant.parse("2030-10-03T15:00:00Z"), "v1", true)));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
        when(store.participant(FESTIVAL, hash))
            .thenReturn(Optional.of(new LoveLetterStore.Participant(PARTICIPANT, false, true)));
        AdminAuditService audit = mock(AdminAuditService.class);
        return new Fixture(store, new LoveLetterService(store, crypto(),
            new FestivalProperties(FESTIVAL.toString()), clock, audit, "http://localhost:5173"), audit);
    }

    private static LoveLetterService.Input input() {
        return new LoveLetterService.Input("MALE", "별명", "한 줄", "@private-contact", true, true, "v1");
    }

    private static LoveLetterStore.Letter candidate() {
        return new LoveLetterStore.Letter(UUID.fromString("44444444-4444-4444-8444-444444444444"),
            UUID.randomUUID(), "FEMALE", "encrypted", "encrypted", "encrypted", "v1", false);
    }

    private static LoveLetterStore.Exchange exchange(Instant createdAt) {
        return new LoveLetterStore.Exchange(UUID.fromString("55555555-5555-4555-8555-555555555555"),
            candidate().id(), false, false, "encrypted", "v1", createdAt);
    }

    private static LoveLetterCrypto crypto() {
        return new LoveLetterCrypto("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "v1");
    }

    private record Fixture(LoveLetterStore store, LoveLetterService service, AdminAuditService audit) {}
    private record Configuration(Instant opensAt, Instant closesAt, String consentVersion) {}
}
