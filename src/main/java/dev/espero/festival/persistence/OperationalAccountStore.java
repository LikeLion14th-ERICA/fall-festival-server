package dev.espero.festival.persistence;

import dev.espero.festival.account.OperationalAccountAuditMetadata;
import dev.espero.festival.account.OperationalAccountChangeAction;
import dev.espero.festival.account.OperationalAccountHistory;
import dev.espero.festival.account.OperationalAccountPurpose;
import dev.espero.festival.account.OperationalAccountSetting;
import dev.espero.festival.account.OperationalAccountState;
import dev.espero.festival.account.OperationalAccountTarget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the current operational account and trigger-owned history. */
@Repository
@Profile("db")
public class OperationalAccountStore {

    private static final String CURRENT_COLUMNS = """
        festival_id, purpose, scope_id, state, version, bank_name, account_number, account_holder,
        transfer_link_url, bank_code, toss_link_enabled, updated_at
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public OperationalAccountStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OperationalAccountSetting> findCurrent(UUID festivalId, OperationalAccountPurpose purpose) {
        return findCurrent(OperationalAccountTarget.festival(festivalId, purpose));
    }

    public Optional<OperationalAccountSetting> findCurrent(OperationalAccountTarget target) {
        return queryCurrent("", target);
    }

    public Optional<OperationalAccountSetting> findCurrentForUpdate(OperationalAccountTarget target) {
        return queryCurrent(" FOR UPDATE", target);
    }

    public Optional<OperationalAccountHistory> findHistory(OperationalAccountTarget target, long version) {
        return jdbc.query("""
            SELECT id, festival_id, purpose, version, after_state, after_bank_name, after_account_number,
                   after_account_holder, after_transfer_link_url, after_bank_code, after_toss_link_enabled,
                   occurred_at
            FROM operational_account_setting_history
            WHERE festival_id = :festivalId AND purpose = :purpose AND scope_id = :scopeId AND version = :version
              AND after_state IS NOT NULL
            ORDER BY id DESC
            LIMIT 1
            """, parameters(target).addValue("version", version),
            (resultSet, rowNumber) -> mapHistory(resultSet)
        ).stream().findFirst();
    }

    /**
     * Keeps version numbers monotonic even if a privileged direct SQL repair has
     * deleted the current row. The retention worker preserves this tuple's latest
     * history event as the durable version watermark.
     */
    public long nextVersion(OperationalAccountTarget target) {
        Long value = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM operational_account_setting_history
            WHERE festival_id = :festivalId AND purpose = :purpose AND scope_id = :scopeId
            """, parameters(target), Long.class);
        return value == null ? 1L : value;
    }

    public void setAuditMetadata(OperationalAccountChangeAction action, OperationalAccountAuditMetadata metadata) {
        jdbc.queryForObject("""
            SELECT set_config('festival.account.operation', :operation, true),
                   set_config('festival.account.actor', :actor, true),
                   set_config('festival.account.reason', :reason, true),
                   set_config('festival.account.evidence_id', :evidenceId, true)
            """, new MapSqlParameterSource()
                .addValue("operation", action.name())
                .addValue("actor", metadata.actor())
                .addValue("reason", metadata.reason())
                .addValue("evidenceId", metadata.evidenceId()),
            (resultSet, rowNumber) -> resultSet.getString(1)
        );
    }

    public void insert(OperationalAccountSetting setting) {
        jdbc.update("""
            INSERT INTO operational_account_settings (
                festival_id, purpose, scope_id, state, version, bank_name, account_number, account_holder,
                transfer_link_url, bank_code, toss_link_enabled, updated_at
            ) VALUES (
                :festivalId, :purpose, :scopeId, :state, :version, :bankName, :accountNumber, :accountHolder,
                :transferLinkUrl, :bankCode, :tossLinkEnabled, :updatedAt
            )
            """, settingParameters(setting));
    }

    public boolean update(OperationalAccountSetting setting, long expectedVersion) {
        return jdbc.update("""
            UPDATE operational_account_settings
            SET state = :state,
                version = :version,
                bank_name = :bankName,
                account_number = :accountNumber,
                account_holder = :accountHolder,
                transfer_link_url = :transferLinkUrl,
                bank_code = :bankCode,
                toss_link_enabled = :tossLinkEnabled,
                updated_at = :updatedAt
            WHERE festival_id = :festivalId AND purpose = :purpose AND scope_id = :scopeId
              AND version = :expectedVersion
            """, settingParameters(setting).addValue("expectedVersion", expectedVersion)) == 1;
    }

    private Optional<OperationalAccountSetting> queryCurrent(String lockSuffix, OperationalAccountTarget target) {
        return jdbc.query("SELECT " + CURRENT_COLUMNS + """
            FROM operational_account_settings
            WHERE festival_id = :festivalId AND purpose = :purpose AND scope_id = :scopeId
            """ + lockSuffix, parameters(target),
            (resultSet, rowNumber) -> mapCurrent(resultSet)
        ).stream().findFirst();
    }

    private MapSqlParameterSource parameters(OperationalAccountTarget target) {
        return new MapSqlParameterSource()
            .addValue("festivalId", target.festivalId())
            .addValue("purpose", target.purpose().name())
            .addValue("scopeId", target.scopeId());
    }

    private MapSqlParameterSource settingParameters(OperationalAccountSetting setting) {
        return parameters(setting.target())
            .addValue("state", setting.state().name())
            .addValue("version", setting.version())
            .addValue("bankName", setting.bankName())
            .addValue("accountNumber", setting.accountNumber())
            .addValue("accountHolder", setting.accountHolder())
            .addValue("transferLinkUrl", setting.transferLinkUrl())
            .addValue("bankCode", setting.bankCode())
            .addValue("tossLinkEnabled", setting.tossLinkEnabled())
            .addValue("updatedAt", OffsetDateTime.ofInstant(setting.updatedAt(), ZoneOffset.UTC));
    }

    private OperationalAccountSetting mapCurrent(ResultSet resultSet) throws SQLException {
        String scopeId = resultSet.getString("scope_id");
        return new OperationalAccountSetting(
            resultSet.getObject("festival_id", UUID.class),
            OperationalAccountPurpose.parse(resultSet.getString("purpose")),
            OperationalAccountState.valueOf(resultSet.getString("state")),
            resultSet.getLong("version"),
            resultSet.getString("bank_name"),
            resultSet.getString("account_number"),
            resultSet.getString("account_holder"),
            resultSet.getString("transfer_link_url"),
            instant(resultSet, "updated_at"),
            scopeId.isEmpty() ? null : scopeId,
            resultSet.getString("bank_code"),
            resultSet.getBoolean("toss_link_enabled")
        );
    }

    private OperationalAccountHistory mapHistory(ResultSet resultSet) throws SQLException {
        String afterState = resultSet.getString("after_state");
        return new OperationalAccountHistory(
            resultSet.getLong("id"),
            resultSet.getObject("festival_id", UUID.class),
            OperationalAccountPurpose.parse(resultSet.getString("purpose")),
            resultSet.getLong("version"),
            afterState == null ? null : OperationalAccountState.valueOf(afterState),
            resultSet.getString("after_bank_name"),
            resultSet.getString("after_account_number"),
            resultSet.getString("after_account_holder"),
            resultSet.getString("after_transfer_link_url"),
            instant(resultSet, "occurred_at"),
            resultSet.getString("after_bank_code"),
            (Boolean) resultSet.getObject("after_toss_link_enabled")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
