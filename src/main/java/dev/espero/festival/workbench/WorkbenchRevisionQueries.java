package dev.espero.festival.workbench;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only revision listing for the workbench; runs under the export role. */
@Repository
@Profile(WorkbenchRoleConfiguration.PROFILE)
class WorkbenchRevisionQueries {

    private final NamedParameterJdbcTemplate jdbc;

    WorkbenchRevisionQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<RevisionSummary> revisions(UUID festivalId) {
        return query("""
            SELECT id, revision_number, state, base_revision_id, created_at, published_at
            FROM festival_revisions
            WHERE festival_id = :festivalId
            ORDER BY revision_number DESC
            LIMIT 50
            """, festivalId);
    }

    Optional<RevisionSummary> published(UUID festivalId) {
        return query("""
            SELECT id, revision_number, state, base_revision_id, created_at, published_at
            FROM festival_revisions
            WHERE festival_id = :festivalId AND state = 'published'
            """, festivalId).stream().findFirst();
    }

    private List<RevisionSummary> query(String sql, UUID festivalId) {
        return jdbc.query(sql, new MapSqlParameterSource("festivalId", festivalId), (resultSet, rowNumber) ->
            new RevisionSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("revision_number"),
                resultSet.getString("state"),
                resultSet.getObject("base_revision_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("published_at", OffsetDateTime.class)
            ));
    }

    record RevisionSummary(
        UUID id,
        long revisionNumber,
        String state,
        UUID baseRevisionId,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt
    ) {}
}
