package dev.espero.festival.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Festival-scoped dynamic counts, separate from revision-scoped artist content. */
@Repository
@Profile("db")
public class ArtistHypedStore {

    private final NamedParameterJdbcTemplate jdbc;

    public ArtistHypedStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Count> currentArtists(UUID festivalId, UUID revisionId) {
        return currentArtists(festivalId, revisionId, "");
    }

    public List<Count> currentArtists(UUID festivalId, UUID revisionId, String keyPrefix) {
        return jdbc.query("""
            SELECT artist.id AS artist_id, COALESCE(hyped.hyped_count, 0) AS hyped_count
            FROM artists artist
            LEFT JOIN artist_hyped_counts hyped
              ON hyped.festival_id = :festivalId AND hyped.artist_id = :keyPrefix || artist.id
            WHERE artist.festival_revision_id = :revisionId AND artist.category = 'ARTIST'
            ORDER BY artist.id
            """, new MapSqlParameterSource()
                .addValue("festivalId", festivalId)
                .addValue("revisionId", revisionId)
                .addValue("keyPrefix", keyPrefix),
            (resultSet, rowNumber) -> new Count(
                resultSet.getString("artist_id"), resultSet.getLong("hyped_count")
            )
        );
    }

    public boolean isCurrentArtist(UUID revisionId, String artistId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM artists
                WHERE festival_revision_id = :revisionId
                  AND id = :artistId
                  AND category = 'ARTIST'
            )
            """, new MapSqlParameterSource()
                .addValue("revisionId", revisionId)
                .addValue("artistId", artistId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** A single PostgreSQL statement serializes concurrent increments on one artist row. */
    public long increment(UUID festivalId, String artistId, Instant now) {
        return increment(festivalId, artistId, 1, now);
    }

    public long increment(UUID festivalId, String artistId, int delta, Instant now) {
        if (delta < 1 || delta > 20) throw new IllegalArgumentException("Hyped delta must be between 1 and 20");
        Long count = jdbc.queryForObject("""
            INSERT INTO artist_hyped_counts (festival_id, artist_id, hyped_count, updated_at)
            VALUES (:festivalId, :artistId, :delta, :updatedAt)
            ON CONFLICT (festival_id, artist_id) DO UPDATE
            SET hyped_count = artist_hyped_counts.hyped_count + EXCLUDED.hyped_count,
                updated_at = EXCLUDED.updated_at
            RETURNING hyped_count
            """, new MapSqlParameterSource()
                .addValue("festivalId", festivalId)
                .addValue("artistId", artistId)
                .addValue("delta", delta)
                .addValue("updatedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)), Long.class);
        if (count == null) {
            throw new IllegalStateException("Artist Hyped increment returned no count.");
        }
        return count;
    }

    public long increment(UUID festivalId, String artistId, String keyPrefix, Instant now) {
        return increment(festivalId, keyPrefix + artistId, now);
    }

    public record Count(String artistId, long hypedCount) {}
}
