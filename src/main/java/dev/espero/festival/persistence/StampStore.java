package dev.espero.festival.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Booth stamp data (V27, daily START V28). Booth tokens are read from the published catalog
 * revision; participants, start days, collections and rewards are festival-scoped
 * operational rows. Callers that change a participant's card lock the
 * participant row first so the daily limit holds under concurrent scans.
 */
@Repository
@Profile("db")
public class StampStore {

    private final NamedParameterJdbcTemplate jdbc;

    public StampStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BoothToken(String boothId, String boothName, LocalDate validDate) {}

    public record Collected(String boothId, Instant collectedAt) {}

    public Optional<BoothToken> findBoothToken(UUID revisionId, String tokenSha256) {
        return jdbc.query("""
            SELECT t.booth_id, b.name, t.valid_date
            FROM stamp_booth_tokens t
            JOIN stamp_booths b ON b.festival_revision_id = t.festival_revision_id AND b.id = t.booth_id
            WHERE t.festival_revision_id = :revisionId AND t.token_sha256 = :tokenSha256
            """, new MapSqlParameterSource().addValue("revisionId", revisionId).addValue("tokenSha256", tokenSha256),
            (resultSet, rowNumber) -> new BoothToken(
                resultSet.getString("booth_id"),
                resultSet.getString("name"),
                resultSet.getObject("valid_date", LocalDate.class)
            )).stream().findFirst();
    }

    /** Booth display names of a revision; a collected booth missing here keeps only its id. */
    public Map<String, String> boothNames(UUID revisionId) {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("""
            SELECT id, name FROM stamp_booths WHERE festival_revision_id = :revisionId ORDER BY sort_order
            """, new MapSqlParameterSource("revisionId", revisionId),
            resultSet -> {
                names.put(resultSet.getString("id"), resultSet.getString("name"));
            });
        return names;
    }

    public UUID createParticipant(UUID festivalId, String tokenSha256, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO stamp_participants (id, festival_id, token_sha256, created_at)
            VALUES (:id, :festivalId, :tokenSha256, :createdAt)
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("festivalId", festivalId)
            .addValue("tokenSha256", tokenSha256)
            .addValue("createdAt", atUtc(now)));
        return id;
    }

    public Optional<UUID> findParticipant(UUID festivalId, String tokenSha256) {
        return jdbc.query("""
            SELECT id FROM stamp_participants WHERE festival_id = :festivalId AND token_sha256 = :tokenSha256
            """, new MapSqlParameterSource().addValue("festivalId", festivalId).addValue("tokenSha256", tokenSha256),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)).stream().findFirst();
    }

    /** Records today's START (V28); returns false when the participant already started today. */
    public boolean startDay(UUID participantId, LocalDate date, Instant now) {
        return jdbc.update("""
            INSERT INTO stamp_participant_days (participant_id, operating_date, started_at)
            VALUES (:participantId, :date, :startedAt)
            ON CONFLICT DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("participantId", participantId)
            .addValue("date", date)
            .addValue("startedAt", atUtc(now))) == 1;
    }

    public boolean startedOn(UUID participantId, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM stamp_participant_days WHERE participant_id = :participantId AND operating_date = :date
            )
            """, new MapSqlParameterSource().addValue("participantId", participantId).addValue("date", date),
            Boolean.class));
    }

    /** Must run inside the caller's transaction. */
    public void lockParticipant(UUID participantId) {
        jdbc.query("SELECT id FROM stamp_participants WHERE id = :id FOR UPDATE",
            new MapSqlParameterSource("id", participantId), resultSet -> {});
    }

    public List<Collected> collections(UUID participantId, LocalDate date) {
        return jdbc.query("""
            SELECT booth_id, collected_at FROM stamp_collections
            WHERE participant_id = :participantId AND operating_date = :date
            ORDER BY collected_at, booth_id
            """, new MapSqlParameterSource().addValue("participantId", participantId).addValue("date", date),
            (resultSet, rowNumber) -> new Collected(
                resultSet.getString("booth_id"),
                resultSet.getObject("collected_at", OffsetDateTime.class).toInstant()
            ));
    }

    public void insertCollection(UUID participantId, LocalDate date, String boothId, Instant now) {
        jdbc.update("""
            INSERT INTO stamp_collections (participant_id, operating_date, booth_id, collected_at)
            VALUES (:participantId, :date, :boothId, :collectedAt)
            """, new MapSqlParameterSource()
            .addValue("participantId", participantId)
            .addValue("date", date)
            .addValue("boothId", boothId)
            .addValue("collectedAt", atUtc(now)));
    }

    public boolean rewardClaimed(UUID participantId, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM stamp_rewards WHERE participant_id = :participantId AND operating_date = :date
            )
            """, new MapSqlParameterSource().addValue("participantId", participantId).addValue("date", date),
            Boolean.class));
    }

    public void insertReward(UUID participantId, LocalDate date, Instant now) {
        jdbc.update("""
            INSERT INTO stamp_rewards (participant_id, operating_date, claimed_at)
            VALUES (:participantId, :date, :claimedAt)
            """, new MapSqlParameterSource()
            .addValue("participantId", participantId)
            .addValue("date", date)
            .addValue("claimedAt", atUtc(now)));
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
