package dev.espero.festival.persistence;

import dev.espero.festival.domain.TicketGuideConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Only active with SPRING_PROFILES_ACTIVE=db; the default profile excludes
 * DataSourceAutoConfiguration, so no NamedParameterJdbcTemplate bean would
 * exist to inject otherwise.
 */
@Repository
@Profile("db")
public class TicketGuideStore {

    private final NamedParameterJdbcTemplate jdbc;

    public TicketGuideStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A ticket guide is only read for the captured published revision. Its
     * map target is loaded by the same catalog snapshot, never as four
     * independently trusted strings from this query.
     *
     * <p>The legacy account and transfer-link columns stay in the table for
     * history but are never selected here: the served account comes from the
     * operational account settings instead.</p>
     */
    public Optional<TicketGuideConfig> find(UUID festivalRevisionId) {
        return find(festivalRevisionId, "ko");
    }

    /**
     * Korean instructions live in the guide row; other locales come from
     * ticket_guide_translations. A locale without a translation row finds no
     * guide rather than the Korean one.
     */
    public Optional<TicketGuideConfig> find(UUID festivalRevisionId, String locale) {
        return jdbc.query("""
            SELECT current.unit_price_amount,
                   CASE WHEN :locale = 'ko' THEN current.instructions ELSE translation.instructions END
                       AS instructions,
                   current.festival_start_date,
                   current.festival_end_date, current.daily_transfer_open_time,
                   current.daily_transfer_close_time, current.daily_pickup_open_time,
                   current.daily_pickup_close_time, current.updated_at
            FROM ticket_guide_revisions current
            LEFT JOIN ticket_guide_translations translation
              ON translation.festival_revision_id = current.festival_revision_id
             AND translation.id = current.id
             AND translation.locale = :locale
            WHERE current.id = 1 AND current.festival_revision_id = :festivalRevisionId
              AND (:locale = 'ko' OR translation.locale IS NOT NULL)
            """, new MapSqlParameterSource("festivalRevisionId", festivalRevisionId).addValue("locale", locale),
            (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    private TicketGuideConfig map(ResultSet resultSet) throws SQLException {
        return new TicketGuideConfig(
            (Integer) resultSet.getObject("unit_price_amount"),
            readStrings(resultSet, "instructions"),
            resultSet.getObject("festival_start_date", LocalDate.class),
            resultSet.getObject("festival_end_date", LocalDate.class),
            resultSet.getObject("daily_transfer_open_time", LocalTime.class),
            resultSet.getObject("daily_transfer_close_time", LocalTime.class),
            resultSet.getObject("daily_pickup_open_time", LocalTime.class),
            resultSet.getObject("daily_pickup_close_time", LocalTime.class),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private List<String> readStrings(ResultSet resultSet, String column) throws SQLException {
        java.sql.Array array = resultSet.getArray(column);
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }
}
