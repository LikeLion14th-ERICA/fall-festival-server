package dev.espero.festival.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("db")
public class ArtistHypedBatchStore {
    private final NamedParameterJdbcTemplate jdbc;

    public ArtistHypedBatchStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Must share one transaction with the count update and completion receipt. */
    public boolean claim(UUID festivalId, UUID batchId, String artistId, int delta, String prefix, Instant now) {
        return jdbc.update("""
            INSERT INTO artist_hyped_batches (festival_id, batch_id, artist_id, delta, count_prefix, created_at)
            VALUES (:festivalId, :batchId, :artistId, :delta, :prefix, :now)
            ON CONFLICT (festival_id, batch_id) DO NOTHING
            """, new MapSqlParameterSource()
                .addValue("festivalId", festivalId).addValue("batchId", batchId)
                .addValue("artistId", artistId).addValue("delta", delta).addValue("prefix", prefix)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))) == 1;
    }

    public Receipt required(UUID festivalId, UUID batchId) {
        Receipt receipt = jdbc.queryForObject("""
            SELECT artist_id, delta, count_prefix, hyped_count FROM artist_hyped_batches
            WHERE festival_id = :festivalId AND batch_id = :batchId
            """, Map.of("festivalId", festivalId, "batchId", batchId), (row, number) ->
                new Receipt(row.getString("artist_id"), row.getInt("delta"), row.getString("count_prefix"),
                    row.getObject("hyped_count", Long.class)));
        if (receipt == null || receipt.hypedCount() == null) {
            throw new IllegalStateException("A committed Hyped batch must have its completion receipt");
        }
        return receipt;
    }

    public void complete(UUID festivalId, UUID batchId, long count) {
        int updated = jdbc.update("""
            UPDATE artist_hyped_batches SET hyped_count = :count
            WHERE festival_id = :festivalId AND batch_id = :batchId AND hyped_count IS NULL
            """, Map.of("festivalId", festivalId, "batchId", batchId, "count", count));
        if (updated != 1) throw new IllegalStateException("Hyped batch completion lost its claim");
    }

    public record Receipt(String artistId, int delta, String countPrefix, Long hypedCount) {}
}
