package dev.espero.festival.persistence;

import dev.espero.festival.domain.AdminAccount;
import dev.espero.festival.domain.AdminRefreshSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("db")
public class AdminAuthStore {

    private static final long BOOTSTRAP_LOCK_KEY = 2_026_091_600_001L;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminAuthStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countAccounts() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_accounts", Map.of(), Long.class);
        return count == null ? 0 : count;
    }

    public void lockBootstrap() {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(:lockKey)",
            new MapSqlParameterSource("lockKey", BOOTSTRAP_LOCK_KEY),
            resultSet -> null
        );
    }

    public Optional<AdminAccount> findAccountByUsername(String username) {
        return jdbc.query("""
            SELECT id, username, password_hash, authority, enabled,
                   created_at, updated_at, last_login_at
            FROM admin_accounts
            WHERE username = :username
            """,
            new MapSqlParameterSource("username", username),
            (resultSet, rowNumber) -> mapAccount(resultSet)
        ).stream().findFirst();
    }

    public Optional<AdminAccount> findAccountById(UUID id) {
        return jdbc.query("""
            SELECT id, username, password_hash, authority, enabled,
                   created_at, updated_at, last_login_at
            FROM admin_accounts
            WHERE id = :id
            """,
            new MapSqlParameterSource("id", id),
            (resultSet, rowNumber) -> mapAccount(resultSet)
        ).stream().findFirst();
    }

    public void insertAccount(AdminAccount account) {
        jdbc.update("""
            INSERT INTO admin_accounts (
                id, username, password_hash, authority, enabled,
                created_at, updated_at, last_login_at
            ) VALUES (
                :id, :username, :passwordHash, :authority, :enabled,
                :createdAt, :updatedAt, :lastLoginAt
            )
            """, accountParameters(account));
    }

    public void updateLastLogin(UUID id, Instant lastLoginAt) {
        jdbc.update("""
            UPDATE admin_accounts
            SET last_login_at = :lastLoginAt, updated_at = :lastLoginAt
            WHERE id = :id
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("lastLoginAt", atUtc(lastLoginAt))
        );
    }

    public Optional<AdminRefreshSession> findRefreshSessionForUpdate(String tokenHash) {
        return jdbc.query("""
            SELECT id, admin_id, token_hash, expires_at, revoked_at, created_at
            FROM admin_refresh_sessions
            WHERE token_hash = :tokenHash
            FOR UPDATE
            """,
            new MapSqlParameterSource("tokenHash", tokenHash),
            (resultSet, rowNumber) -> mapRefreshSession(resultSet)
        ).stream().findFirst();
    }

    public void insertRefreshSession(AdminRefreshSession session) {
        jdbc.update("""
            INSERT INTO admin_refresh_sessions (
                id, admin_id, token_hash, expires_at, revoked_at, created_at
            ) VALUES (
                :id, :adminId, :tokenHash, :expiresAt, :revokedAt, :createdAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("id", session.id())
                .addValue("adminId", session.adminId())
                .addValue("tokenHash", session.tokenHash())
                .addValue("expiresAt", atUtc(session.expiresAt()))
                .addValue("revokedAt", nullableAtUtc(session.revokedAt()))
                .addValue("createdAt", atUtc(session.createdAt()))
        );
    }

    public boolean revokeRefreshSession(UUID id, Instant revokedAt) {
        return jdbc.update("""
            UPDATE admin_refresh_sessions
            SET revoked_at = :revokedAt
            WHERE id = :id AND revoked_at IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("revokedAt", atUtc(revokedAt))
        ) == 1;
    }

    public void revokeRefreshSessionForAdmin(String tokenHash, UUID adminId, Instant revokedAt) {
        jdbc.update("""
            UPDATE admin_refresh_sessions
            SET revoked_at = :revokedAt
            WHERE token_hash = :tokenHash
              AND admin_id = :adminId
              AND revoked_at IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("tokenHash", tokenHash)
                .addValue("adminId", adminId)
                .addValue("revokedAt", atUtc(revokedAt))
        );
    }

    private MapSqlParameterSource accountParameters(AdminAccount account) {
        return new MapSqlParameterSource()
            .addValue("id", account.id())
            .addValue("username", account.username())
            .addValue("passwordHash", account.passwordHash())
            .addValue("authority", account.authority())
            .addValue("enabled", account.enabled())
            .addValue("createdAt", atUtc(account.createdAt()))
            .addValue("updatedAt", atUtc(account.updatedAt()))
            .addValue("lastLoginAt", nullableAtUtc(account.lastLoginAt()));
    }

    private AdminAccount mapAccount(ResultSet resultSet) throws SQLException {
        return new AdminAccount(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("username"),
            resultSet.getString("password_hash"),
            resultSet.getString("authority"),
            resultSet.getBoolean("enabled"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            nullableInstant(resultSet, "last_login_at")
        );
    }

    private AdminRefreshSession mapRefreshSession(ResultSet resultSet) throws SQLException {
        return new AdminRefreshSession(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("admin_id", UUID.class),
            resultSet.getString("token_hash"),
            instant(resultSet, "expires_at"),
            nullableInstant(resultSet, "revoked_at"),
            instant(resultSet, "created_at")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private OffsetDateTime nullableAtUtc(Instant instant) {
        return instant == null ? null : atUtc(instant);
    }
}
