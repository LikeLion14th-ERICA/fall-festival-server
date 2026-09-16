package dev.espero.festival.persistence;

import dev.espero.festival.domain.PublishedFestivalContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("db")
public class FestivalContextStore {

    private final NamedParameterJdbcTemplate jdbc;

    public FestivalContextStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PublishedFestivalContext> findPublishedByFestivalId(UUID festivalId) {
        return jdbc.query("""
            SELECT f.id AS festival_id,
                   f.timezone,
                   fr.id AS festival_revision_id,
                   fr.revision_number
            FROM festivals f
            JOIN festival_revisions fr
              ON fr.festival_id = f.id
            WHERE f.id = :festivalId
              AND fr.state = 'published'
            """,
            new MapSqlParameterSource("festivalId", festivalId),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    private PublishedFestivalContext map(ResultSet resultSet) throws SQLException {
        return new PublishedFestivalContext(
            resultSet.getObject("festival_id", UUID.class),
            resultSet.getObject("festival_revision_id", UUID.class),
            resultSet.getLong("revision_number"),
            ZoneId.of(resultSet.getString("timezone"))
        );
    }
}
