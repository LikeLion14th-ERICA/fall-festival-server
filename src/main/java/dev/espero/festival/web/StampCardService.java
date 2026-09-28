package dev.espero.festival.web;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.StampStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booth stamp rules (STAMP-001). A participant is an anonymous browser that
 * holds a random cookie value; the server stores only its SHA-256. A
 * participant presses START once per festival day (in the festival's time
 * zone); until then the card is not started and booth QRs collect nothing.
 * After START it collects at most one stamp per booth and {@link #DAILY_LIMIT}
 * stamps that day, and claims the reward once after collecting all of them.
 */
@Service
@Profile("db")
public class StampCardService {

    static final int DAILY_LIMIT = 4;
    private static final LocalTime REWARD_OPENS = LocalTime.of(11, 0);
    private static final LocalTime REWARD_CLOSES = LocalTime.of(17, 0);
    private static final Pattern PARTICIPANT_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern BOOTH_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");

    private final StampStore store;
    private final CatalogSnapshotProvider snapshots;
    private final FestivalProperties festival;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public StampCardService(
        StampStore store,
        CatalogSnapshotProvider snapshots,
        FestivalProperties festival,
        Clock clock
    ) {
        this.store = store;
        this.snapshots = snapshots;
        this.festival = festival;
        this.clock = clock;
    }

    /**
     * Today's card. {@code startedNow} is true when this call recorded today's
     * START; {@code cookieToken} is then the cookie value to (re)issue, so the
     * cookie lifetime counts from the latest START.
     */
    public record Started(StampCardResponse card, boolean startedNow, String cookieToken) {}

    /** Records today's START, creating the participant for a browser without a valid cookie. */
    @Transactional(timeoutString = "${festival.stamp.transaction-timeout-seconds:3}")
    public Started start(String participantToken) {
        CatalogSnapshot snapshot = snapshots.required();
        Instant instant = clock.instant();
        LocalDate today = instant.atZone(snapshot.context().timezone()).toLocalDate();
        requireEventDay(snapshot, today);
        Optional<UUID> existing = participant(participantToken);
        String token = existing.isPresent() ? participantToken : newParticipantToken();
        UUID participant = existing.orElseGet(
            () -> store.createParticipant(festival.configuredFestivalId(), sha256(token), instant)
        );
        boolean startedNow = store.startDay(participant, today, instant);
        return new Started(card(participant, snapshot, today), startedNow, startedNow ? token : null);
    }

    /** Today's card; {@code STAMP_NOT_STARTED} until today's START, which shows the start screen. */
    @Transactional(readOnly = true, timeoutString = "${festival.stamp.transaction-timeout-seconds:3}")
    public StampCardResponse current(String participantToken) {
        UUID participant = participant(participantToken).orElseThrow(StampCardService::notStarted);
        CatalogSnapshot snapshot = snapshots.required();
        LocalDate today = clock.instant().atZone(snapshot.context().timezone()).toLocalDate();
        requireStartedToday(participant, today);
        return card(participant, snapshot, today);
    }

    @Transactional(timeoutString = "${festival.stamp.transaction-timeout-seconds:3}")
    public StampCardResponse collect(String participantToken, String boothToken) {
        UUID participant = participant(participantToken).orElseThrow(StampCardService::notStarted);
        store.lockParticipant(participant);
        CatalogSnapshot snapshot = snapshots.required();
        // PostgreSQL timestamps have microsecond precision; reuse exactly the persisted value in the response.
        Instant instant = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        LocalDate today = instant.atZone(snapshot.context().timezone()).toLocalDate();
        requireEventDay(snapshot, today);
        requireStartedToday(participant, today);
        StampStore.BoothToken booth = boothToken == null || !BOOTH_TOKEN.matcher(boothToken).matches()
            ? null
            : store.findBoothToken(snapshot.context().revisionId(), sha256(boothToken)).orElse(null);
        if (booth == null || (booth.validDate() != null && !booth.validDate().equals(today))) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STAMP_TOKEN", "스탬프투어 QR이 아니에요.", false
            );
        }
        if (store.rewardClaimed(participant, today)) {
            throw conflict("STAMP_REWARD_CLAIMED", "오늘은 이미 상품을 받았어요.");
        }
        List<StampStore.NamedCollected> collected = store.namedCollections(participant, today, snapshot.context().revisionId());
        if (collected.stream().anyMatch(stamp -> stamp.boothId().equals(booth.boothId()))) {
            throw conflict("STAMP_ALREADY_COLLECTED", "이 부스의 스탬프는 오늘 이미 받았어요.");
        }
        if (collected.size() >= DAILY_LIMIT) {
            throw conflict("STAMP_CARD_FULL", "오늘 받을 수 있는 스탬프를 모두 모았어요.");
        }
        store.insertCollection(participant, today, booth.boothId(), instant);
        List<StampStore.NamedCollected> updated = new ArrayList<>(collected);
        updated.add(new StampStore.NamedCollected(booth.boothId(), booth.boothName(), instant));
        // The participant lock protects this snapshot through commit; no second DB read is needed.
        return card(snapshot, today, updated, false);
    }

    /**
     * Records today's reward for a participant who holds a full card. The
     * caller has already checked the staff reward code.
     */
    @Transactional(timeoutString = "${festival.stamp.transaction-timeout-seconds:3}")
    public void claimReward(String participantToken) {
        UUID participant = participant(participantToken).orElseThrow(StampCardService::incomplete);
        store.lockParticipant(participant);
        CatalogSnapshot snapshot = snapshots.required();
        Instant instant = clock.instant();
        LocalDate today = instant.atZone(snapshot.context().timezone()).toLocalDate();
        requireEventDay(snapshot, today);
        if (store.rewardClaimed(participant, today)) {
            throw conflict("STAMP_REWARD_CLAIMED", "오늘은 이미 상품을 받았어요.");
        }
        if (store.collections(participant, today).size() < DAILY_LIMIT) {
            throw incomplete();
        }
        LocalTime now = instant.atZone(snapshot.context().timezone()).toLocalTime();
        if (now.isBefore(REWARD_OPENS) || !now.isBefore(REWARD_CLOSES)) {
            throw conflict("STAMP_REWARD_CLOSED", "상품 수령은 11:00부터 17:00 전까지 가능해요.");
        }
        store.insertReward(participant, today, instant);
    }

    private StampCardResponse card(UUID participant, CatalogSnapshot snapshot, LocalDate today) {
        return card(snapshot, today, store.namedCollections(participant, today, snapshot.context().revisionId()),
            store.rewardClaimed(participant, today));
    }

    private StampCardResponse card(CatalogSnapshot snapshot, LocalDate today,
        List<StampStore.NamedCollected> collected, boolean rewardClaimed) {
        List<StampCardResponse.Stamp> stamps = collected.stream()
            .sorted(Comparator.comparing(StampStore.NamedCollected::collectedAt)
                .thenComparing(StampStore.NamedCollected::boothId))
            .map(stamp -> new StampCardResponse.Stamp(
                stamp.boothId(),
                stamp.boothName(),
                OffsetDateTime.ofInstant(stamp.collectedAt(), snapshot.context().timezone())
            ))
            .toList();
        return new StampCardResponse(today, DAILY_LIMIT, stamps, rewardClaimed);
    }

    private void requireStartedToday(UUID participant, LocalDate today) {
        if (!store.startedOn(participant, today)) {
            throw notStarted();
        }
    }

    private Optional<UUID> participant(String participantToken) {
        if (participantToken == null || !PARTICIPANT_TOKEN.matcher(participantToken).matches()) {
            return Optional.empty();
        }
        return store.findParticipant(festival.configuredFestivalId(), sha256(participantToken));
    }

    private void requireEventDay(CatalogSnapshot snapshot, LocalDate today) {
        if (snapshot.stampGuide() == null || snapshot.stampGuide().dates().isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STAMP_GUIDE_NOT_CONFIGURED",
                "스탬프 안내가 아직 설정되지 않았습니다.", true);
        }
        if (!snapshot.stampGuide().dates().contains(today)) {
            throw conflict("STAMP_EVENT_CLOSED", "스탬프투어 행사 기간이 아니에요.");
        }
    }

    private String newParticipantToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ApiException notStarted() {
        return new ApiException(HttpStatus.NOT_FOUND, "STAMP_NOT_STARTED", "스탬프투어를 먼저 시작해 주세요.", false);
    }

    private static ApiException incomplete() {
        return conflict("STAMP_CARD_INCOMPLETE", "스탬프 4개를 모두 모아야 상품을 받을 수 있어요.");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, false);
    }
}
