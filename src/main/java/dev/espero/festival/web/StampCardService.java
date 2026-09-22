package dev.espero.festival.web;

import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.CatalogSnapshot;
import dev.espero.festival.persistence.StampStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
    @Transactional
    public Started start(String participantToken) {
        LocalDate today = today(snapshots.required());
        Optional<UUID> existing = participant(participantToken);
        String token = existing.isPresent() ? participantToken : newParticipantToken();
        UUID participant = existing.orElseGet(
            () -> store.createParticipant(festival.configuredFestivalId(), sha256(token), clock.instant())
        );
        boolean startedNow = store.startDay(participant, today, clock.instant());
        return new Started(card(participant), startedNow, startedNow ? token : null);
    }

    /** Today's card; {@code STAMP_NOT_STARTED} until today's START, which shows the start screen. */
    @Transactional(readOnly = true)
    public StampCardResponse current(String participantToken) {
        UUID participant = participant(participantToken).orElseThrow(StampCardService::notStarted);
        requireStartedToday(participant, today(snapshots.required()));
        return card(participant);
    }

    @Transactional
    public StampCardResponse collect(String participantToken, String boothToken) {
        UUID participant = participant(participantToken).orElseThrow(StampCardService::notStarted);
        store.lockParticipant(participant);
        CatalogSnapshot snapshot = snapshots.required();
        LocalDate today = today(snapshot);
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
        List<StampStore.Collected> collected = store.collections(participant, today);
        if (collected.stream().anyMatch(stamp -> stamp.boothId().equals(booth.boothId()))) {
            throw conflict("STAMP_ALREADY_COLLECTED", "이 부스의 스탬프는 오늘 이미 받았어요.");
        }
        if (collected.size() >= DAILY_LIMIT) {
            throw conflict("STAMP_CARD_FULL", "오늘 받을 수 있는 스탬프를 모두 모았어요.");
        }
        store.insertCollection(participant, today, booth.boothId(), clock.instant());
        return card(participant);
    }

    /**
     * Records today's reward for a participant who holds a full card. The
     * caller has already checked the staff reward code.
     */
    @Transactional
    public void claimReward(String participantToken) {
        UUID participant = participant(participantToken).orElseThrow(StampCardService::incomplete);
        store.lockParticipant(participant);
        LocalDate today = today(snapshots.required());
        if (store.rewardClaimed(participant, today)) {
            throw conflict("STAMP_REWARD_CLAIMED", "오늘은 이미 상품을 받았어요.");
        }
        if (store.collections(participant, today).size() < DAILY_LIMIT) {
            throw incomplete();
        }
        store.insertReward(participant, today, clock.instant());
    }

    private StampCardResponse card(UUID participant) {
        CatalogSnapshot snapshot = snapshots.required();
        LocalDate today = today(snapshot);
        Map<String, String> names = store.boothNames(snapshot.context().revisionId());
        List<StampCardResponse.Stamp> stamps = store.collections(participant, today).stream()
            .map(stamp -> new StampCardResponse.Stamp(
                stamp.boothId(),
                names.get(stamp.boothId()),
                OffsetDateTime.ofInstant(stamp.collectedAt(), snapshot.context().timezone())
            ))
            .toList();
        return new StampCardResponse(today, DAILY_LIMIT, stamps, store.rewardClaimed(participant, today));
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

    private LocalDate today(CatalogSnapshot snapshot) {
        return LocalDate.now(clock.withZone(snapshot.context().timezone()));
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
