package dev.espero.festival.web;

import dev.espero.festival.auth.AdminAuditService;
import dev.espero.festival.context.FestivalProperties;
import dev.espero.festival.domain.AdminAuditAction;
import dev.espero.festival.domain.AdminAuditResourceType;
import dev.espero.festival.persistence.LoveLetterStore;
import dev.espero.festival.persistence.LoveLetterStore.Exchange;
import dev.espero.festival.persistence.LoveLetterStore.Participant;
import dev.espero.festival.persistence.LoveLetterStore.Settings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** LOVE-001 registration and drawing are separate serialized transactions. */
@Service
@Profile("db")
public class LoveLetterService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final LoveLetterStore store;
    private final LoveLetterCrypto crypto;
    private final FestivalProperties festival;
    private final Clock clock;
    private final AdminAuditService audit;
    private final String allowedOrigin;
    private final SecureRandom random = new SecureRandom();

    public LoveLetterService(LoveLetterStore store, LoveLetterCrypto crypto, FestivalProperties festival, Clock clock,
                             AdminAuditService audit,
                             @Value("${festival.love-letter.allowed-origin:}") String allowedOrigin) {
        this.store = store;
        this.crypto = crypto;
        this.festival = festival;
        this.clock = clock;
        this.audit = audit;
        this.allowedOrigin = allowedOrigin;
    }

    public record Guide(boolean enabled, Instant opensAt, Instant closesAt, String consentVersion,
                        String timezone, String participationRule, int minimumAge,
                        int maxNameChars, int maxMessageChars, int maxContactChars,
                        boolean ownContactConfirmationRequired, boolean disclosureConsentRequired,
                        int drawDelaySeconds) {}
    public record Session(String cookie, String csrfToken, Status status) {
        @Override public String toString() { return "LoveLetterSession[REDACTED]"; }
    }
    public record Status(String state, boolean canParticipate, Instant nextParticipationAt,
                         UUID exchangeId, String contact, String csrfToken,
                         boolean canDraw, Instant drawAvailableAt) {
        @Override public String toString() { return "LoveLetterStatus[state=" + state + ", contact=REDACTED]"; }
    }
    public record Draw(UUID exchangeId, String state) {}
    public record Registered(UUID letterId, Instant drawAvailableAt, String state) {}
    public record Opened(UUID exchangeId, String name, String message, String contact) {
        @Override public String toString() { return "LoveLetterOpened[REDACTED]"; }
    }
    public record AdminReport(UUID id, UUID letterId, UUID authorId, UUID reporterId,
                              String name, String message, String contact, boolean blocked) {
        @Override public String toString() { return "LoveLetterAdminReport[REDACTED]"; }
    }
    public record Input(String gender, String name, String message, String contact,
                        boolean adultConfirmed, boolean ownContactConfirmed, String consentVersion) {
        @Override public String toString() { return "LoveLetterInput[REDACTED]"; }
    }
    public record SeedInput(LocalDate operatingDate, Input letter, Instant consentAt) {
        @Override public String toString() { return "LoveLetterSeedInput[REDACTED]"; }
    }
    public record Seeded(UUID participantId, String invitationToken) {
        @Override public String toString() { return "LoveLetterSeeded[REDACTED]"; }
    }

    public Guide guide() {
        Settings settings = store.settings(festivalId()).orElse(null);
        return settings == null ? new Guide(false, null, null, null, SEOUL.getId(), "BROWSER_DAILY", 19, 20, 100, 100, true, true, 60)
            : new Guide(settings.enabled() && crypto.configured() && !allowedOrigin.isBlank() && inPeriod(settings),
                settings.opensAt(), settings.closesAt(), settings.consentVersion(), SEOUL.getId(), "BROWSER_DAILY",
                19, 20, 100, 100, true, true, 60);
    }

    @Transactional
    public Session start(String oldToken) {
        active();
        Participant prior = optionalParticipant(oldToken);
        if (prior != null) return new Session(null, csrf(oldToken), status(oldToken));
        String token = token();
        store.createParticipant(festivalId(), sha256(token), clock.instant());
        return new Session(token, csrf(token), status(token));
    }

    public Status status(String token) {
        Settings settings = store.settings(festivalId()).orElse(null);
        if (settings == null || !settings.enabled() || !crypto.configured() || allowedOrigin.isBlank() ||
            !clock.instant().isBefore(settings.closesAt()))
            return new Status("CLOSED", false, null, null, null, null, false, null);
        if (clock.instant().isBefore(settings.opensAt()))
            return new Status("BEFORE_OPEN", false, settings.opensAt(), null, null, null, false, null);
        Participant participant = optionalParticipant(token);
        if (participant == null) return new Status("WRITABLE", true, null, null, null, null, false, null);
        if (participant.restricted()) return new Status("RESTRICTED", false, null, null, null, csrf(token), false, null);
        var day = store.dayLetter(participant.id(), today()).orElse(null);
        if (day != null && !"COMPLETED".equals(day.state())) {
            Instant available = day.createdAt().plusSeconds(60);
            boolean ready = !clock.instant().isBefore(available);
            return new Status(ready ? ("SEEDED".equals(day.state()) ? "SEEDED" : "DRAW_READY") : "WAITING",
                false, today().plusDays(1).atStartOfDay(SEOUL).toInstant(), null, null, csrf(token), ready, available);
        }
        Exchange latest = store.latestExchange(participant.id()).orElse(null);
        boolean done = store.participated(participant.id(), today());
        Instant next = done ? today().plusDays(1).atStartOfDay(SEOUL).toInstant() : null;
        if (latest != null && latest.blocked())
            return new Status("RESULT_BLOCKED", !done, next, latest.id(), null, csrf(token), false, null);
        if (latest != null && latest.opened())
            return new Status("OPENED", !done, next, latest.id(), crypto.decrypt(latest.contact(), latest.keyVersion()), csrf(token), false, null);
        if (latest != null) return new Status("SEALED", !done, next, latest.id(), null, csrf(token), false, null);
        return new Status("WRITABLE", true, null, null, null, csrf(token), false, null);
    }

    @Transactional
    public Registered register(String token, Input input, String idempotencyKey) {
        Settings settings = active();
        validate(input, settings.consentVersion());
        checkKey(idempotencyKey);
        store.lockFestival(festivalId());
        Participant participant = requiredParticipant(token);
        ensureAllowed(participant);
        String digest = crypto.hmac("register\u0000" + input.gender() + "\u0000" + input.name() + "\u0000"
            + input.message() + "\u0000" + input.contact() + "\u0000" + input.consentVersion());
        Registered replay = registrationReplay(participant.id(), idempotencyKey, digest);
        if (replay != null) return replay;
        if (store.participated(participant.id(), today()) || store.seededLetter(participant.id(), today()).isPresent())
            throw conflict("LOVE_ALREADY_PARTICIPATED", "오늘은 이미 참여했습니다.");
        UUID letterId = UUID.randomUUID();
        store.insertLetter(letterId, festivalId(), participant.id(), today(), input.gender(),
            crypto.encrypt(input.name().strip()), crypto.encrypt(input.message().strip()),
            crypto.encrypt(input.contact().strip()), crypto.version(), settings.consentVersion(), clock.instant());
        store.createPendingDay(participant.id(), today(), letterId);
        store.saveRequest(participant.id(), today(), idempotencyKey, digest, letterId, null);
        return new Registered(letterId, clock.instant().plusSeconds(60), "WAITING");
    }

    @Transactional
    public Draw draw(String token, String idempotencyKey) {
        active();
        checkKey(idempotencyKey);
        store.lockFestival(festivalId());
        Participant participant = requiredParticipant(token);
        ensureAllowed(participant);
        String digest = crypto.hmac("draw");
        Draw replay = drawReplay(participant.id(), idempotencyKey, digest);
        if (replay != null) return replay;
        var day = store.dayLetter(participant.id(), today())
            .orElseThrow(() -> conflict("LOVE_NOT_REGISTERED", "오늘 등록한 쪽지가 없습니다."));
        if ("COMPLETED".equals(day.state())) throw conflict("LOVE_ALREADY_PARTICIPATED", "오늘은 이미 추첨했습니다.");
        if (clock.instant().isBefore(day.createdAt().plusSeconds(60)))
            throw new ApiException(HttpStatus.CONFLICT, "LOVE_WAITING", "작성 후 1분이 지나면 추첨할 수 있습니다.", true);
        var candidate = store.randomCandidate(festivalId(), participant.id(), opposite(day.gender()), today())
            .orElseThrow(LoveLetterService::poolEmpty);
        UUID exchange = store.insertExchange(festivalId(), participant.id(), candidate.id(), today(), clock.instant());
        store.completeDay(participant.id(), today(), day.letterId(), clock.instant());
        store.saveRequest(participant.id(), today(), idempotencyKey, digest, null, exchange);
        return new Draw(exchange, "SEALED");
    }

    @Transactional
    public Draw drawSeeded(String token, String idempotencyKey) { return draw(token, idempotencyKey); }

    @Transactional
    public Opened open(String token, UUID id) {
        active();
        store.lockFestival(festivalId());
        Participant participant = requiredParticipant(token);
        ensureAllowed(participant);
        Exchange exchange = store.exchange(participant.id(), id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LOVE_RESULT_NOT_FOUND", "쪽지를 찾지 못했습니다.", false));
        if (!store.latestExchange(participant.id()).map(latest -> latest.id().equals(id)).orElse(false)) throw notFound();
        if (exchange.blocked()) throw new ApiException(HttpStatus.FORBIDDEN, "LOVE_RESULT_BLOCKED", "차단된 쪽지입니다.", false);
        var letter = store.letter(exchange.letterId()).orElseThrow();
        store.markOpened(id, clock.instant());
        return new Opened(id, crypto.decrypt(letter.name(), letter.keyVersion()),
            crypto.decrypt(letter.body(), letter.keyVersion()), crypto.decrypt(letter.contact(), letter.keyVersion()));
    }

    @Transactional
    public void report(String token, UUID id) {
        active();
        Participant participant = requiredParticipant(token);
        ensureAllowed(participant);
        if (!store.latestExchange(participant.id()).map(latest -> latest.id().equals(id)).orElse(false)) throw notFound();
        store.report(id, participant.id(), clock.instant());
    }

    @Transactional
    public Seeded seed(SeedInput seed, String requestId) {
        Settings settings = store.settings(festivalId()).orElseThrow(() -> conflict("LOVE_UNCONFIGURED", "운영 설정이 없습니다."));
        if (!clock.instant().isBefore(settings.closesAt())) throw conflict("LOVE_CLOSED", "운영이 종료되었습니다.");
        if (seed == null || seed.operatingDate() == null || seed.consentAt() == null ||
            seed.consentAt().isAfter(clock.instant()) ||
            !seed.operatingDate().plusDays(1).atStartOfDay(SEOUL).toInstant().isAfter(settings.opensAt()) ||
            !seed.operatingDate().atStartOfDay(SEOUL).toInstant().isBefore(settings.closesAt()))
            throw badInput();
        validate(seed.letter(), settings.consentVersion());
        store.lockFestival(festivalId());
        UUID participant = store.seedParticipant(festivalId(), clock.instant());
        UUID letter = UUID.randomUUID();
        Input input = seed.letter();
        store.insertLetter(letter, festivalId(), participant, seed.operatingDate(), input.gender(),
            crypto.encrypt(input.name().strip()), crypto.encrypt(input.message().strip()),
            crypto.encrypt(input.contact().strip()), crypto.version(), settings.consentVersion(), seed.consentAt());
        store.seedDay(participant, seed.operatingDate(), letter);
        String invitation = token();
        store.invite(participant, sha256(invitation), seed.operatingDate(), clock.instant());
        audit.record(AdminAuditAction.LOVE_SEEDED, AdminAuditResourceType.LOVE_PARTICIPANT, participant.toString(), requestId);
        return new Seeded(participant, invitation);
    }

    @Transactional
    public Seeded reissue(UUID participantId, String requestId) {
        Settings settings = store.settings(festivalId()).orElseThrow(() -> conflict("LOVE_UNCONFIGURED", "운영 설정이 없습니다."));
        if (!clock.instant().isBefore(settings.closesAt())) throw conflict("LOVE_CLOSED", "운영이 종료되었습니다.");
        store.lockFestival(festivalId());
        Participant participant = store.participantById(festivalId(), participantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LOVE_PARTICIPANT_NOT_FOUND", "참여자를 찾지 못했습니다.", false));
        if (participant.restricted()) throw conflict("LOVE_RESTRICTED", "참여가 제한되었습니다.");
        if (participant.bound()) throw conflict("LOVE_INVITATION_INVALID", "이미 연결된 참여자입니다.");
        LocalDate date = store.seededDate(participantId)
            .orElseThrow(() -> conflict("LOVE_SEED_NOT_AVAILABLE", "사용할 사전 쪽지가 없습니다."));
        if (date.isBefore(today())) throw conflict("LOVE_SEED_NOT_AVAILABLE", "지정 참여일이 지났습니다.");
        store.invalidateInvitations(participantId, clock.instant());
        String invitation = token();
        store.invite(participantId, sha256(invitation), date, clock.instant());
        audit.record(AdminAuditAction.LOVE_INVITATION_REISSUED, AdminAuditResourceType.LOVE_PARTICIPANT, participantId.toString(), requestId);
        return new Seeded(participantId, invitation);
    }

    @Transactional
    public Session claim(String currentToken, String invitationToken) {
        active();
        if (invitationToken == null || !invitationToken.matches("[A-Za-z0-9_-]{32,128}")) throw badInput();
        store.lockFestival(festivalId());
        Participant current = requiredParticipant(currentToken);
        ensureAllowed(current);
        if (store.participated(current.id(), today()) || store.seededLetter(current.id(), today()).isPresent())
            throw conflict("LOVE_ALREADY_PARTICIPATED", "오늘은 이미 참여했습니다.");
        var invitation = store.invitation(sha256(invitationToken))
            .orElseThrow(() -> conflict("LOVE_INVITATION_INVALID", "연결 링크가 유효하지 않습니다."));
        if (invitation.used() || !invitation.date().equals(today()))
            throw conflict("LOVE_INVITATION_INVALID", "연결 링크가 유효하지 않습니다.");
        if (invitation.participantId().equals(current.id())) throw conflict("LOVE_INVITATION_INVALID", "이미 연결되었습니다.");
        Participant invited = store.participantById(festivalId(), invitation.participantId())
            .orElseThrow(() -> conflict("LOVE_INVITATION_INVALID", "연결 링크가 유효하지 않습니다."));
        if (invited.restricted() || invited.bound()) throw conflict("LOVE_INVITATION_INVALID", "연결 링크가 유효하지 않습니다.");
        if (!store.consumeInvitation(invitation.id(), clock.instant())) throw conflict("LOVE_INVITATION_INVALID", "연결 링크가 유효하지 않습니다.");
        String newToken = token();
        if (!store.bindParticipant(invitation.participantId(), sha256(newToken)))
            throw conflict("LOVE_INVITATION_INVALID", "이미 연결되었습니다.");
        return new Session(newToken, csrf(newToken), status(newToken));
    }

    @Transactional
    public void block(UUID letterId, String requestId) {
        store.lockFestival(festivalId());
        if (!store.blockLetter(festivalId(), letterId)) throw notFound();
        audit.record(AdminAuditAction.LOVE_LETTER_BLOCKED, AdminAuditResourceType.LOVE_LETTER, letterId.toString(), requestId);
    }
    @Transactional
    public void restrict(UUID participantId, boolean restricted, String requestId) {
        store.lockFestival(festivalId());
        if (!store.restrictParticipant(festivalId(), participantId, restricted)) throw notFound();
        audit.record(restricted ? AdminAuditAction.LOVE_PARTICIPANT_RESTRICTED : AdminAuditAction.LOVE_PARTICIPANT_UNRESTRICTED,
            AdminAuditResourceType.LOVE_PARTICIPANT, participantId.toString(), requestId);
    }
    @Transactional
    public void configure(Instant opensAt, Instant closesAt, String consentVersion, String requestId) {
        if (opensAt == null || closesAt == null || !opensAt.isBefore(closesAt) ||
            consentVersion == null || !consentVersion.matches("[A-Za-z0-9._-]{1,64}")) throw badInput();
        store.lockFestival(festivalId());
        if (store.participantCount(festivalId()) > 0) throw conflict("LOVE_CONFIGURATION_LOCKED", "참여가 시작되어 운영 설정을 변경할 수 없습니다.");
        store.configure(festivalId(), opensAt, closesAt, consentVersion);
        audit.record(AdminAuditAction.LOVE_CONFIGURED, AdminAuditResourceType.LOVE_SETTINGS, festivalId().toString(), requestId);
    }
    @Transactional
    public void enabled(boolean enabled, String requestId) {
        store.lockFestival(festivalId());
        if (enabled && !crypto.configured()) throw conflict("LOVE_KEY_UNCONFIGURED", "암호화 키가 설정되지 않았습니다.");
        if (enabled && allowedOrigin.isBlank()) throw conflict("LOVE_ORIGIN_UNCONFIGURED", "허용 origin이 설정되지 않았습니다.");
        Settings settings = store.settings(festivalId()).orElseThrow(() -> conflict("LOVE_UNCONFIGURED", "운영 설정이 없습니다."));
        if (enabled && !clock.instant().isBefore(settings.closesAt())) throw conflict("LOVE_CLOSED", "운영이 종료되었습니다.");
        LocalDate poolDate = today().isBefore(settings.opensAt().atZone(SEOUL).toLocalDate())
            ? settings.opensAt().atZone(SEOUL).toLocalDate() : today();
        if (enabled && (store.poolCount(festivalId(), "MALE", poolDate) < 1 || store.poolCount(festivalId(), "FEMALE", poolDate) < 1))
            throw conflict("LOVE_SEED_REQUIRED", "남녀 초기 쪽지를 모두 준비해야 합니다.");
        if (!store.setEnabled(festivalId(), enabled)) throw conflict("LOVE_UNCONFIGURED", "운영 설정이 없습니다.");
        audit.record(AdminAuditAction.LOVE_ENABLED_CHANGED, AdminAuditResourceType.LOVE_SETTINGS, festivalId().toString(), requestId);
    }
    @Transactional
    public List<LoveLetterStore.Report> reports(String requestId) {
        var reports = store.reports(festivalId());
        audit.record(AdminAuditAction.LOVE_REPORTS_VIEWED, AdminAuditResourceType.LOVE_SETTINGS, festivalId().toString(), requestId);
        return reports;
    }

    @Transactional
    public AdminReport reportDetail(UUID reportId, String requestId) {
        Settings settings = store.settings(festivalId()).orElseThrow(LoveLetterService::notFound);
        if (!clock.instant().isBefore(settings.closesAt())) throw conflict("LOVE_CLOSED", "운영이 종료되었습니다.");
        var detail = store.reportDetail(festivalId(), reportId).orElseThrow(LoveLetterService::notFound);
        audit.record(AdminAuditAction.LOVE_REPORT_VIEWED, AdminAuditResourceType.LOVE_LETTER,
            detail.letterId().toString(), requestId);
        return new AdminReport(detail.id(), detail.letterId(), detail.authorId(), detail.reporterId(),
            crypto.decrypt(detail.name(), detail.keyVersion()), crypto.decrypt(detail.body(), detail.keyVersion()),
            crypto.decrypt(detail.contact(), detail.keyVersion()), detail.blocked());
    }

    public String csrf(String token) { return token == null ? null : crypto.hmac("csrf\u0000" + token); }

    private Registered registrationReplay(UUID participant, String key, String digest) {
        try {
            return store.request(participant, key, digest).map(result -> new Registered(result.letterId(),
                store.letterCreatedAt(result.letterId()).orElseThrow().plusSeconds(60), "WAITING")).orElse(null);
        } catch (IllegalArgumentException error) {
            throw conflict("LOVE_IDEMPOTENCY_CONFLICT", "요청 키가 다른 내용에 재사용되었습니다.");
        }
    }

    private Draw drawReplay(UUID participant, String key, String digest) {
        try {
            return store.request(participant, key, digest).map(result -> new Draw(result.exchangeId(), "SEALED")).orElse(null);
        } catch (IllegalArgumentException error) {
            throw conflict("LOVE_IDEMPOTENCY_CONFLICT", "요청 키가 다른 내용에 재사용되었습니다.");
        }
    }

    private Settings active() {
        Settings settings = store.settings(festivalId()).orElse(null);
        if (settings == null || !settings.enabled() || !crypto.configured() || allowedOrigin.isBlank() || !inPeriod(settings))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LOVE_CLOSED", "러브레터 운영 시간이 아닙니다.", false);
        return settings;
    }
    private boolean inPeriod(Settings settings) {
        Instant now = clock.instant();
        return !now.isBefore(settings.opensAt()) && now.isBefore(settings.closesAt());
    }
    private Participant requiredParticipant(String token) {
        Participant participant = optionalParticipant(token);
        if (participant == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "LOVE_SESSION_REQUIRED", "참여 브라우저 세션이 필요합니다.", false);
        return participant;
    }
    private Participant optionalParticipant(String token) {
        return token == null || !token.matches("[A-Za-z0-9_-]{32,128}") ? null
            : store.participant(festivalId(), sha256(token)).orElse(null);
    }
    private void ensureAllowed(Participant participant) {
        if (participant.restricted()) throw new ApiException(HttpStatus.FORBIDDEN, "LOVE_RESTRICTED", "참여가 제한되었습니다.", false);
    }
    private void validate(Input input, String consentVersion) {
        if (input == null || input.gender() == null || !List.of("MALE", "FEMALE").contains(input.gender()) ||
            !valid(input.name(), 20, false) || !valid(input.message(), 100, true) ||
            !valid(input.contact(), 100, false) || !input.adultConfirmed() ||
            !input.ownContactConfirmed() || !consentVersion.equals(input.consentVersion())) throw badInput();
    }
    private static boolean valid(String value, int max, boolean oneLine) {
        return value != null && !value.isBlank() && value.strip().codePointCount(0, value.strip().length()) <= max &&
            value.chars().noneMatch(c -> Character.isISOControl(c) || (oneLine && (c == '\n' || c == '\r')));
    }
    private static void checkKey(String key) {
        if (key == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "LOVE_IDEMPOTENCY_KEY_REQUIRED", "요청 키가 필요합니다.", false);
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw badInput();
    }
    private static ApiException badInput() { return new ApiException(HttpStatus.BAD_REQUEST, "LOVE_INVALID_INPUT", "입력을 확인해 주세요.", false); }
    private static ApiException poolEmpty() { return new ApiException(HttpStatus.CONFLICT, "LOVE_POOL_EMPTY", "받을 수 있는 쪽지가 아직 없습니다.", true); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "LOVE_NOT_FOUND", "대상을 찾지 못했습니다.", false); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message, false); }
    private static String opposite(String gender) { return "MALE".equals(gender) ? "FEMALE" : "MALE"; }
    private UUID festivalId() { return festival.configuredFestivalId(); }
    private LocalDate today() { return LocalDate.now(clock.withZone(SEOUL)); }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
