package dev.espero.festival.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("db")
public class LoveLetterStore {
    private final NamedParameterJdbcTemplate jdbc;

    public LoveLetterStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Settings(Instant opensAt, Instant closesAt, String consentVersion, boolean enabled) {}
    public record Participant(UUID id, boolean restricted, boolean bound) {}
    public record Letter(UUID id, UUID authorId, String gender, String name, String body,
                         String contact, String keyVersion, boolean blocked) {}
    public record Exchange(UUID id, UUID letterId, boolean opened, boolean blocked,
                           String contact, String keyVersion, Instant createdAt) {}
    public record DayLetter(UUID letterId, String gender, String state, Instant createdAt) {}
    public record RequestResult(UUID letterId, UUID exchangeId) {}
    public record Invitation(UUID id, UUID participantId, LocalDate date, boolean used) {}
    public record Report(UUID id, UUID letterId, UUID reporterId, Instant createdAt) {}
    public record ReportDetail(UUID id, UUID letterId, UUID authorId, UUID reporterId,
                               String name, String body, String contact, String keyVersion, boolean blocked) {}

    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }

    public Optional<Settings> settings(UUID festivalId) {
        return jdbc.query("SELECT opens_at, closes_at, consent_version, enabled FROM love_letter_settings WHERE festival_id=:festival",
            new MapSqlParameterSource("festival", festivalId), (rs, n) -> new Settings(
                rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getObject(2, OffsetDateTime.class).toInstant(),
                rs.getString(3), rs.getBoolean(4))).stream().findFirst();
    }

    public void configure(UUID festival, Instant opensAt, Instant closesAt, String consentVersion) {
        jdbc.update("""
            INSERT INTO love_letter_settings(festival_id,opens_at,closes_at,consent_version,enabled)
            VALUES (:festival,:opens,:closes,:version,FALSE)
            ON CONFLICT (festival_id) DO UPDATE SET opens_at=EXCLUDED.opens_at,
              closes_at=EXCLUDED.closes_at,consent_version=EXCLUDED.consent_version,enabled=FALSE
            """, new MapSqlParameterSource().addValue("festival", festival).addValue("opens", at(opensAt))
                .addValue("closes", at(closesAt)).addValue("version", consentVersion));
    }

    public void lockFestival(UUID festivalId) {
        jdbc.query("SELECT pg_advisory_xact_lock(20260924, hashtext(:festival))",
            new MapSqlParameterSource("festival", festivalId.toString()), (rs, n) -> rs.getObject(1));
    }

    public long poolCount(UUID festivalId, String gender, LocalDate date) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM love_letters l WHERE l.festival_id=:festival AND l.gender=:gender
            AND l.created_date<=:date
            AND NOT l.blocked AND NOT EXISTS (SELECT 1 FROM love_letter_exchanges e WHERE e.letter_id=l.id)
            AND NOT EXISTS (SELECT 1 FROM love_letter_participants p WHERE p.id=l.author_id AND p.restricted)
            """, new MapSqlParameterSource().addValue("festival", festivalId).addValue("gender", gender).addValue("date", date), Long.class);
    }

    public Optional<Participant> participant(UUID festivalId, String hash) {
        return jdbc.query("SELECT id, restricted, token_sha256 IS NOT NULL FROM love_letter_participants WHERE festival_id=:festival AND token_sha256=:hash",
            new MapSqlParameterSource().addValue("festival", festivalId).addValue("hash", hash),
            (rs, n) -> new Participant(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3))).stream().findFirst();
    }

    public Optional<Participant> participantById(UUID festival, UUID id) {
        return jdbc.query("SELECT id, restricted, token_sha256 IS NOT NULL FROM love_letter_participants WHERE festival_id=:festival AND id=:id",
            new MapSqlParameterSource().addValue("festival", festival).addValue("id", id),
            (rs, n) -> new Participant(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3))).stream().findFirst();
    }

    public long participantCount(UUID festival) {
        return jdbc.queryForObject("SELECT count(*) FROM love_letter_participants WHERE festival_id=:festival",
            new MapSqlParameterSource("festival", festival), Long.class);
    }

    public UUID createParticipant(UUID festivalId, String hash, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO love_letter_participants (id,festival_id,token_sha256,created_at) VALUES (:id,:festival,:hash,:now)",
            new MapSqlParameterSource().addValue("id", id).addValue("festival", festivalId)
                .addValue("hash", hash).addValue("now", at(now)));
        return id;
    }

    public boolean bindParticipant(UUID id, String hash) {
        return jdbc.update("UPDATE love_letter_participants SET token_sha256=:hash WHERE id=:id AND token_sha256 IS NULL",
            new MapSqlParameterSource().addValue("id", id).addValue("hash", hash)) == 1;
    }

    public boolean participated(UUID participant, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM love_letter_participation_days WHERE participant_id=:participant
              AND operating_date=:date AND state IN ('PENDING','COMPLETED'))
            """, new MapSqlParameterSource().addValue("participant", participant).addValue("date", date), Boolean.class));
    }

    public Optional<UUID> seededLetter(UUID participant, LocalDate date) {
        return jdbc.query("""
            SELECT letter_id FROM love_letter_participation_days WHERE participant_id=:participant
            AND operating_date=:date AND state='SEEDED'
            """, new MapSqlParameterSource().addValue("participant", participant).addValue("date", date),
            (rs, n) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    public Optional<DayLetter> dayLetter(UUID participant, LocalDate date) {
        return jdbc.query("""
            SELECT d.letter_id,l.gender,d.state,l.created_at
            FROM love_letter_participation_days d JOIN love_letters l ON l.id=d.letter_id
            WHERE d.participant_id=:participant AND d.operating_date=:date
            """, new MapSqlParameterSource().addValue("participant", participant).addValue("date", date),
            (rs, n) -> new DayLetter(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class).toInstant())).stream().findFirst();
    }

    public List<UUID> boundSeededParticipants(UUID festival, LocalDate date) {
        return jdbc.query("""
            SELECT d.participant_id FROM love_letter_participation_days d
            JOIN love_letter_participants p ON p.id=d.participant_id
            JOIN love_letters own ON own.id=d.letter_id
            WHERE p.festival_id=:festival AND d.operating_date=:date AND d.state='SEEDED'
              AND p.token_sha256 IS NOT NULL AND NOT p.restricted
              AND EXISTS (
                SELECT 1 FROM love_letters candidate
                JOIN love_letter_participants author ON author.id=candidate.author_id
                WHERE candidate.festival_id=:festival AND candidate.gender<>own.gender
                  AND candidate.author_id<>d.participant_id AND candidate.created_date<=:date
                  AND NOT candidate.blocked AND NOT author.restricted
                  AND NOT EXISTS (SELECT 1 FROM love_letter_exchanges used WHERE used.letter_id=candidate.id)
              )
            ORDER BY p.created_at LIMIT 100
            """, new MapSqlParameterSource().addValue("festival", festival).addValue("date", date),
            (rs,n) -> rs.getObject(1, UUID.class));
    }

    public Optional<Instant> letterCreatedAt(UUID id) {
        return jdbc.query("SELECT created_at FROM love_letters WHERE id=:id", new MapSqlParameterSource("id", id),
            (rs,n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).stream().findFirst();
    }

    public void createPendingDay(UUID participant, LocalDate date, UUID letter) {
        jdbc.update("""
            INSERT INTO love_letter_participation_days(participant_id,operating_date,letter_id,state)
            VALUES (:participant,:date,:letter,'PENDING')
            """, new MapSqlParameterSource().addValue("participant", participant).addValue("date", date).addValue("letter", letter));
    }

    public Optional<LocalDate> seededDate(UUID participant) {
        return jdbc.query("SELECT operating_date FROM love_letter_participation_days WHERE participant_id=:participant AND state='SEEDED' ORDER BY operating_date DESC LIMIT 1",
            new MapSqlParameterSource("participant", participant),
            (rs, n) -> rs.getObject(1, LocalDate.class)).stream().findFirst();
    }

    public void insertLetter(UUID id, UUID festival, UUID author, LocalDate date, String gender,
                             String name, String body, String contact, String keyVersion,
                             String consentVersion, Instant consentAt) {
        jdbc.update("""
            INSERT INTO love_letters (id,festival_id,author_id,created_date,gender,name_cipher,body_cipher,
              contact_cipher,key_version,consent_version,consent_at,created_at)
            VALUES (:id,:festival,:author,:date,:gender,:name,:body,:contact,:keyVersion,:consentVersion,:now,:now)
            """, new MapSqlParameterSource().addValue("id", id).addValue("festival", festival)
            .addValue("author", author).addValue("date", date).addValue("gender", gender)
            .addValue("name", name).addValue("body", body).addValue("contact", contact)
            .addValue("keyVersion", keyVersion).addValue("consentVersion", consentVersion)
            .addValue("now", at(consentAt)));
    }

    public Optional<Letter> randomCandidate(UUID festival, UUID receiver, String gender, LocalDate date) {
        return jdbc.query("""
            SELECT l.id,l.author_id,l.gender,l.name_cipher,l.body_cipher,l.contact_cipher,l.key_version,l.blocked
            FROM love_letters l JOIN love_letter_participants p ON p.id=l.author_id
            WHERE l.festival_id=:festival AND l.gender=:gender AND l.author_id<>:receiver
              AND l.created_date<=:date
              AND NOT l.blocked AND NOT p.restricted
              AND NOT EXISTS (SELECT 1 FROM love_letter_exchanges e WHERE e.letter_id=l.id)
            ORDER BY random() LIMIT 1
            """, new MapSqlParameterSource().addValue("festival", festival).addValue("receiver", receiver)
            .addValue("gender", gender).addValue("date", date), (rs, n) -> letter(rs)).stream().findFirst();
    }

    public Optional<Letter> letter(UUID id) {
        return jdbc.query("SELECT id,author_id,gender,name_cipher,body_cipher,contact_cipher,key_version,blocked FROM love_letters WHERE id=:id",
            new MapSqlParameterSource("id", id), (rs, n) -> letter(rs)).stream().findFirst();
    }

    private static Letter letter(ResultSet rs) throws SQLException {
        return new Letter(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8));
    }

    public void completeDay(UUID participant, LocalDate date, UUID letter, Instant now) {
        MapSqlParameterSource values = new MapSqlParameterSource().addValue("participant", participant)
            .addValue("date", date).addValue("letter", letter).addValue("now", at(now));
        if (jdbc.update("""
            UPDATE love_letter_participation_days SET state='COMPLETED', completed_at=:now
            WHERE participant_id=:participant AND operating_date=:date AND letter_id=:letter AND state IN ('PENDING','SEEDED')
            """, values) != 1) throw new IllegalStateException("Daily letter is not drawable");
    }

    public Optional<RequestResult> request(UUID participant, String key, String digest) {
        return jdbc.query("SELECT letter_id,exchange_id,request_hmac FROM love_letter_requests WHERE participant_id=:participant AND idempotency_key=:key",
            new MapSqlParameterSource().addValue("participant", participant).addValue("key", key),
            (rs, n) -> {
                if (!digest.equals(rs.getString(3))) throw new IllegalArgumentException("Idempotency key reused with different request");
                return new RequestResult(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
            }).stream().findFirst();
    }

    public void saveRequest(UUID participant, LocalDate date, String key, String digest, UUID letter, UUID exchange) {
        jdbc.update("INSERT INTO love_letter_requests(participant_id,operating_date,idempotency_key,request_hmac,letter_id,exchange_id) VALUES (:participant,:date,:key,:digest,:letter,:exchange)",
            new MapSqlParameterSource().addValue("participant", participant).addValue("date", date)
                .addValue("key", key).addValue("digest", digest).addValue("letter", letter).addValue("exchange", exchange));
    }

    public UUID insertExchange(UUID festival, UUID receiver, UUID letter, LocalDate date, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO love_letter_exchanges (id,festival_id,receiver_id,letter_id,operating_date,created_at)
            VALUES (:id,:festival,:receiver,:letter,:date,:now)
            """, new MapSqlParameterSource().addValue("id", id).addValue("festival", festival)
            .addValue("receiver", receiver).addValue("letter", letter).addValue("date", date).addValue("now", at(now)));
        return id;
    }

    public Optional<Exchange> exchange(UUID receiver, UUID exchange) {
        return jdbc.query("""
            SELECT e.id,e.letter_id,e.opened_at IS NOT NULL,l.blocked,l.contact_cipher,l.key_version,e.created_at
            FROM love_letter_exchanges e JOIN love_letters l ON l.id=e.letter_id
            WHERE e.id=:exchange AND e.receiver_id=:receiver
            """, new MapSqlParameterSource().addValue("exchange", exchange).addValue("receiver", receiver),
            (rs, n) -> new Exchange(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getBoolean(3), rs.getBoolean(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class).toInstant())).stream().findFirst();
    }

    public Optional<Exchange> latestExchange(UUID receiver) {
        return jdbc.query("""
            SELECT e.id,e.letter_id,e.opened_at IS NOT NULL,l.blocked,l.contact_cipher,l.key_version,e.created_at
            FROM love_letter_exchanges e JOIN love_letters l ON l.id=e.letter_id
            WHERE e.receiver_id=:receiver ORDER BY e.operating_date DESC,e.created_at DESC,e.id DESC LIMIT 1
            """, new MapSqlParameterSource("receiver", receiver),
            (rs, n) -> new Exchange(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getBoolean(3), rs.getBoolean(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class).toInstant())).stream().findFirst();
    }

    public void markOpened(UUID id, Instant now) {
        jdbc.update("UPDATE love_letter_exchanges SET opened_at=COALESCE(opened_at,:now) WHERE id=:id",
            new MapSqlParameterSource().addValue("id", id).addValue("now", at(now)));
    }

    public void report(UUID exchange, UUID reporter, Instant now) {
        jdbc.update("""
            INSERT INTO love_letter_reports (id,exchange_id,reporter_id,created_at)
            VALUES (:id,:exchange,:reporter,:now) ON CONFLICT (exchange_id,reporter_id) DO NOTHING
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("exchange", exchange)
            .addValue("reporter", reporter).addValue("now", at(now)));
    }

    public UUID seedParticipant(UUID festival, Instant now) { return createParticipant(festival, null, now); }

    public void seedDay(UUID participant, LocalDate date, UUID letter) {
        jdbc.update("""
            INSERT INTO love_letter_participation_days(participant_id,operating_date,letter_id,state)
            VALUES (:participant,:date,:letter,'SEEDED')
            """, new MapSqlParameterSource().addValue("participant", participant).addValue("date", date).addValue("letter", letter));
    }

    public void invalidateInvitations(UUID participant, Instant now) {
        jdbc.update("UPDATE love_letter_invitations SET invalidated_at=:now WHERE participant_id=:participant AND consumed_at IS NULL AND invalidated_at IS NULL",
            new MapSqlParameterSource().addValue("participant", participant).addValue("now", at(now)));
    }

    public void invite(UUID participant, String hash, LocalDate date, Instant now) {
        jdbc.update("""
            INSERT INTO love_letter_invitations(id,participant_id,token_sha256,operating_date,created_at)
            VALUES (:id,:participant,:hash,:date,:now)
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("participant", participant)
            .addValue("hash", hash).addValue("date", date).addValue("now", at(now)));
    }

    public Optional<Invitation> invitation(String hash) {
        return jdbc.query("""
            SELECT id,participant_id,operating_date,consumed_at IS NOT NULL
            FROM love_letter_invitations WHERE token_sha256=:hash AND invalidated_at IS NULL
            """, new MapSqlParameterSource("hash", hash), (rs, n) -> new Invitation(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, LocalDate.class),
            rs.getBoolean(4))).stream().findFirst();
    }

    public boolean consumeInvitation(UUID id, Instant now) {
        return jdbc.update("UPDATE love_letter_invitations SET consumed_at=:now WHERE id=:id AND consumed_at IS NULL AND invalidated_at IS NULL",
            new MapSqlParameterSource().addValue("id", id).addValue("now", at(now))) == 1;
    }

    public boolean blockLetter(UUID festival, UUID id) {
        return jdbc.update("UPDATE love_letters SET blocked=TRUE WHERE festival_id=:festival AND id=:id",
            new MapSqlParameterSource().addValue("festival", festival).addValue("id", id)) == 1;
    }

    public boolean restrictParticipant(UUID festival, UUID id, boolean restricted) {
        return jdbc.update("UPDATE love_letter_participants SET restricted=:restricted WHERE festival_id=:festival AND id=:id",
            new MapSqlParameterSource().addValue("festival", festival).addValue("id", id).addValue("restricted", restricted)) == 1;
    }

    public boolean setEnabled(UUID festival, boolean enabled) {
        return jdbc.update("UPDATE love_letter_settings SET enabled=:enabled WHERE festival_id=:festival",
            new MapSqlParameterSource().addValue("festival", festival).addValue("enabled", enabled)) == 1;
    }

    public List<Report> reports(UUID festival) {
        return jdbc.query("""
            SELECT r.id,e.letter_id,r.reporter_id,r.created_at
            FROM love_letter_reports r JOIN love_letter_exchanges e ON e.id=r.exchange_id
            WHERE e.festival_id=:festival
            ORDER BY r.created_at DESC
            """, new MapSqlParameterSource("festival", festival), (rs, n) -> new Report(rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, OffsetDateTime.class).toInstant()));
    }

    public Optional<ReportDetail> reportDetail(UUID festival, UUID report) {
        return jdbc.query("""
            SELECT r.id,l.id,l.author_id,r.reporter_id,l.name_cipher,l.body_cipher,l.contact_cipher,l.key_version,l.blocked
            FROM love_letter_reports r
            JOIN love_letter_exchanges e ON e.id=r.exchange_id
            JOIN love_letters l ON l.id=e.letter_id
            WHERE e.festival_id=:festival AND r.id=:report
            """, new MapSqlParameterSource().addValue("festival", festival).addValue("report", report),
            (rs,n) -> new ReportDetail(rs.getObject(1, UUID.class),rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),rs.getObject(4, UUID.class),rs.getString(5),rs.getString(6),
                rs.getString(7),rs.getString(8),rs.getBoolean(9))).stream().findFirst();
    }
}
