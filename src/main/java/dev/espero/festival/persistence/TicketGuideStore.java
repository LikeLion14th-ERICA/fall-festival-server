package dev.espero.festival.persistence;

import dev.espero.festival.domain.TicketGuideConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
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

    public Optional<TicketGuideConfig> find() {
        return jdbc.query("""
            SELECT unit_price_amount, account_bank_name, account_number, account_holder,
                   transfer_link_label, transfer_link_url, map_id, place_id, pin_id,
                   map_version, instructions, festival_start_date, festival_end_date,
                   daily_transfer_open_time, daily_transfer_close_time,
                   daily_pickup_open_time, daily_pickup_close_time, updated_at
            FROM ticket_guide
            WHERE id = 1
            """, Map.of(), (resultSet, rowNumber) -> map(resultSet)
        ).stream().findFirst();
    }

    private TicketGuideConfig map(ResultSet resultSet) throws SQLException {
        return new TicketGuideConfig(
            (Integer) resultSet.getObject("unit_price_amount"),
            resultSet.getString("account_bank_name"),
            resultSet.getString("account_number"),
            resultSet.getString("account_holder"),
            resultSet.getString("transfer_link_label"),
            resultSet.getString("transfer_link_url"),
            resultSet.getString("map_id"),
            resultSet.getString("place_id"),
            resultSet.getString("pin_id"),
            resultSet.getString("map_version"),
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
