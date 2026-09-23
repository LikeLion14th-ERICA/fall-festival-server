package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.persistence.LoveLetterStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoveLetterServiceTest {
    private static final UUID FESTIVAL = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final UUID PARTICIPANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TOKEN = "test-anonymous-participant-token-1234567890";
    private static final LocalDate DAY = LocalDate.parse("2030-10-01");

    @Test
    void registrationPersistsPendingLetterWithoutDrawing() throws Exception {
        Fixture fixture = fixture();
        when(fixture.store.request(eq(PARTICIPANT), eq("retry-key"), any())).thenReturn(Optional.empty());
        assertThat(fixture.service.register(TOKEN, input(), "retry-key").state()).isEqualTo("WAITING");
        verify(fixture.store).createPendingDay(eq(PARTICIPANT), eq(DAY), any());
        verify(fixture.store, never()).randomCandidate(any(), any(), any(), any());
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
    void earlyDrawLeavesCandidatePoolUntouched() throws Exception {
        Fixture fixture = fixture();
        UUID letter = UUID.fromString("33333333-3333-4333-8333-333333333333");
        when(fixture.store.dayLetter(PARTICIPANT, DAY)).thenReturn(Optional.of(new LoveLetterStore.DayLetter(
            letter, "MALE", "PENDING", Instant.parse("2030-10-01T03:00:00Z"))));
        assertThatThrownBy(() -> fixture.service.draw(TOKEN, "draw-key"))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("LOVE_WAITING");
        verify(fixture.store, never()).randomCandidate(any(), any(), any(), any());
    }

    @Test
    void emptyPoolAfterMinuteKeepsPendingRegistration() throws Exception {
        Fixture fixture = fixture();
        UUID letter = UUID.fromString("33333333-3333-4333-8333-333333333333");
        when(fixture.store.dayLetter(PARTICIPANT, DAY)).thenReturn(Optional.of(new LoveLetterStore.DayLetter(
            letter, "MALE", "PENDING", Instant.parse("2030-10-01T02:58:00Z"))));
        when(fixture.store.randomCandidate(FESTIVAL, PARTICIPANT, "FEMALE", DAY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> fixture.service.draw(TOKEN, "draw-key"))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("LOVE_POOL_EMPTY");
        verify(fixture.store, never()).completeDay(any(), any(), any(), any());
    }

    @Test
    void statusEnablesDrawAtExactlySixtySeconds() throws Exception {
        UUID letter = UUID.fromString("33333333-3333-4333-8333-333333333333");
        Instant written = Instant.parse("2030-10-01T03:00:00Z");
        Fixture waiting = fixture(written.plusSeconds(59));
        when(waiting.store.dayLetter(PARTICIPANT, DAY)).thenReturn(Optional.of(
            new LoveLetterStore.DayLetter(letter, "MALE", "PENDING", written)));
        assertThat(waiting.service.status(TOKEN).state()).isEqualTo("WAITING");
        assertThat(waiting.service.status(TOKEN).canDraw()).isFalse();
        Fixture ready = fixture(written.plusSeconds(60));
        when(ready.store.dayLetter(PARTICIPANT, DAY)).thenReturn(Optional.of(
            new LoveLetterStore.DayLetter(letter, "MALE", "PENDING", written)));
        assertThat(ready.service.status(TOKEN).state()).isEqualTo("DRAW_READY");
        assertThat(ready.service.status(TOKEN).canDraw()).isTrue();
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
            Instant.parse("2030-10-01T00:00:00Z"), Instant.parse("2030-10-03T13:00:00Z"), "v1", true)));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
        when(store.participant(FESTIVAL, hash))
            .thenReturn(Optional.of(new LoveLetterStore.Participant(PARTICIPANT, false, true)));
        return new Fixture(store, new LoveLetterService(store, crypto(),
            new FestivalProperties(FESTIVAL.toString()), clock, mock(AdminAuditService.class), "http://localhost:5173"));
    }

    private static LoveLetterService.Input input() {
        return new LoveLetterService.Input("MALE", "별명", "한 줄", "@private-contact", true, true, "v1");
    }

    private static LoveLetterCrypto crypto() {
        return new LoveLetterCrypto("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "v1");
    }

    private record Fixture(LoveLetterStore store, LoveLetterService service) {}
}
