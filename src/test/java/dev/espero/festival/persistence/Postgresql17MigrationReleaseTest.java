package dev.espero.festival.persistence;

import static dev.espero.festival.support.ApiMetaTestFixtures.FESTIVAL_ID;
import static dev.espero.festival.support.ApiMetaTestFixtures.REVISION_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.espero.festival.support.PostgresTestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Release-only migrations; every connection comes from this disposable container. */
@Tag("release-pg17")
@Testcontainers
class Postgresql17MigrationReleaseTest {
    private static final String GOODS_ID = "01234567-89ab-4cde-8fab-012345678901";
    private static final String NOTICE_ID = "01234567-89ab-4cde-8fab-012345678902";
    private static final String MEDIA_ID = "01234567-89ab-4cde-8fab-012345678903";
    private static final String STAMP_PARTICIPANT_ONE = "01234567-89ab-4cde-8fab-012345678920";
    private static final String STAMP_PARTICIPANT_TWO = "01234567-89ab-4cde-8fab-012345678921";
    private static final String STAMP_PARTICIPANT_THREE = "01234567-89ab-4cde-8fab-012345678922";
    private static final String LOVE_AUTHOR_ID = "01234567-89ab-4cde-8fab-012345678930";
    private static final String LOVE_RECEIVER_ID = "01234567-89ab-4cde-8fab-012345678931";
    private static final String LOVE_INVITED_ID = "01234567-89ab-4cde-8fab-012345678932";
    private static final String LOVE_LETTER_ID = "01234567-89ab-4cde-8fab-012345678933";
    private static final String LOVE_OTHER_LETTER_ID = "01234567-89ab-4cde-8fab-012345678934";
    private static final String LOVE_EXCHANGE_ID = "01234567-89ab-4cde-8fab-012345678935";
    private static final String LOVE_REPORT_ID = "01234567-89ab-4cde-8fab-012345678936";
    private static final String LOVE_INVITATION_ID = "01234567-89ab-4cde-8fab-012345678937";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImages.image());

    private String databaseUrl;

    @BeforeEach
    void disposablePostgresql17Database() throws SQLException {
        assertThat(PostgresTestImages.image()).isEqualTo(PostgresTestImages.POSTGRES_17);
        String database = "migration_release_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        databaseUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + database;
        assertThat(text("SHOW server_version_num")).isEqualTo("170011");
    }

    @Test
    void freshV1ThroughV30ValidatesChecksumsAndRestartsWithoutMigrations() throws SQLException {
        assertThat(flyway(30).migrate().migrationsExecuted).isEqualTo(30);
        assertHistory(30);
        assertThat(text("SELECT count(*) FROM media_assets")).isEqualTo("0");
        assertThat(text("SELECT count(*) FROM goods_images")).isEqualTo("0");
        assertThat(text("SELECT count(*) FROM goods_image_translations")).isEqualTo("0");
        assertThat(text("SELECT count(*) FROM festival_title_translations")).isEqualTo("0");
        assertLoveLetterTablesAreEmpty();
        assertTemplatePlaceholder();
        assertRestartIsUnchanged();
    }

    @Test
    void v23DataAndConstraintsSurviveSequentialMigrationsThroughV30AndRestart() throws SQLException {
        assertThat(flyway(23).migrate().migrationsExecuted).isEqualTo(23);
        assertHistory(23);
        seedV23State();
        assertThat(text("SELECT count(*) FROM festival_links WHERE kind='WELCOME_DAY'")).isEqualTo("1");
        Map<String, String> preserved = snapshot(tables(), true);
        for (int version = 24; version <= 30; version++) {
            List<String> previousHistory = history();
            assertThat(flyway(version).migrate().migrationsExecuted).isOne();
            assertHistory(version);
            assertThat(history().subList(0, version - 1)).isEqualTo(previousHistory);
            if (version == 29) {
                Map<String, String> unaffected = new LinkedHashMap<>(preserved);
                unaffected.remove("festival_links");
                unaffected.remove("festival_link_translations");
                assertThat(snapshot(unaffected.keySet().stream().toList(), true)).isEqualTo(unaffected);
            } else {
                assertThat(snapshot(preserved.keySet().stream().toList(), true)).isEqualTo(preserved);
            }
            switch (version) {
                case 24 -> verifyTranslations();
                case 25 -> verifyGoodsMedia();
                case 26 -> verifyNoticeTemplates();
                case 27 -> verifyBoothStampSchema();
                case 28 -> verifyStampDailyBackfill();
                case 29 -> verifyWelcomeDayRemoval();
                case 30 -> verifyLoveLetterSchemaAndCascades();
                default -> throw new AssertionError("Unexpected migration target");
            }
            preserved = snapshot(tables(), true);
        }

        // Existing constraints and the V23 trigger must still protect operational state.
        rejects("UPDATE goods SET price_amount = -1", "23514");
        rejects("UPDATE notices SET category = 'OTHER'", "23514");
        rejects("UPDATE operational_account_settings SET version = 1", "23514");
        execute("UPDATE operational_account_settings SET version = 2");
        assertThat(text("SELECT count(*) FROM operational_account_setting_history")).isEqualTo("2");
        assertThat(text("SELECT operation FROM operational_account_setting_history WHERE version = 2"))
            .isEqualTo("DIRECT_SQL");
        assertRestartIsUnchanged();
    }

    private void seedV23State() throws SQLException {
        // Local fixtures only. No credentials, external configuration or operational content are imported.
        execute("""
            INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version)
            VALUES ('%1$s', 'release-map', 'OVERVIEW', 1, 'v2');
            INSERT INTO map_asset_versions
                (festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height)
            VALUES
                ('%1$s', 'release-map', 'v1', '/test/old.png', '이전 지도', 800, 600),
                ('%1$s', 'release-map', 'v2', '/test/current.png', '현재 지도', 800, 600);
            INSERT INTO places (festival_revision_id, id, kind)
            VALUES ('%1$s', 'release-place', 'FACILITY');
            INSERT INTO map_pins
                (festival_revision_id, map_id, map_version, id, category, filter_group, x, y, place_id)
            VALUES ('%1$s', 'release-map', 'v2', 'release-pin', 'toilet', 'RESTROOM', 0.25, 0.75,
                'release-place');
            UPDATE ticket_guide_revisions
            SET instructions = ARRAY['기존 안내'], map_id = 'release-map', map_version = 'v2',
                place_id = 'release-place', pin_id = 'release-pin';
            INSERT INTO goods (id, festival_id, option_mode, price_amount)
            VALUES ('%3$s', '%2$s', 'SINGLE', 1000);
            INSERT INTO goods_translations (goods_id, locale, name, description)
            VALUES ('%3$s', 'ko', '검증 상품', '기존 설명');
            INSERT INTO goods_combinations (id, goods_id, availability)
            VALUES ('01234567-89ab-4cde-8fab-012345678904', '%3$s', 'SOLD_OUT');
            INSERT INTO notices (id, festival_id, category)
            VALUES ('%4$s', '%2$s', 'GENERAL');
            INSERT INTO notice_translations (notice_id, locale, title, body)
            VALUES ('%4$s', 'ko', '기존 공지', '보존할 본문');
            INSERT INTO operational_account_settings (festival_id, purpose, state, version)
            VALUES ('%2$s', 'TICKET', 'UNCONFIGURED', 1);
            INSERT INTO festival_links (festival_revision_id, id, kind, url, sort_order)
            VALUES ('%1$s', 'release-welcome', 'WELCOME_DAY', 'https://example.test/welcome', 1);
            INSERT INTO festival_link_translations (festival_revision_id, link_id, locale, label)
            VALUES ('%1$s', 'release-welcome', 'ko', 'Welcome fixture');
            INSERT INTO catalog_revision_audit
                (festival_id, revision_id, action, actor, created_at)
            VALUES ('%2$s', '%1$s', 'IMPORT', 'migration-test', CURRENT_TIMESTAMP);
            """.formatted(REVISION_ID, FESTIVAL_ID, GOODS_ID, NOTICE_ID));
    }

    private void verifyTranslations() throws SQLException {
        execute("""
            INSERT INTO festival_title_translations (festival_revision_id, locale, title)
            VALUES ('%1$s', 'en', 'Release fixture');
            INSERT INTO map_asset_translations (festival_revision_id, map_id, version, locale, image_alt)
            VALUES ('%1$s', 'release-map', 'v1', 'en', 'Previous map');
            INSERT INTO ticket_guide_translations (festival_revision_id, id, locale, instructions)
            VALUES ('%1$s', 1, 'en', ARRAY['Preserved instructions']);
            INSERT INTO stamp_guide_translations
                (festival_revision_id, id, locale, title, reward_name, reward_notice)
            VALUES ('%1$s', 1, 'en', 'Stamp fixture', 'Reward fixture', 'Fixture notice');
            """.formatted(REVISION_ID));
        rejects("UPDATE festival_title_translations SET locale = 'ko'", "23514");
        rejects("UPDATE map_asset_translations SET image_alt = ' '", "23514");
        rejects("UPDATE map_asset_translations SET version = 'missing'", "23503");
        rejects("UPDATE stamp_guide_translations SET id = 2", "23503");
        rejects("UPDATE ticket_guide_translations SET id = 2", "23503");
    }

    private void verifyGoodsMedia() throws SQLException {
        assertThat(text("SELECT count(*) FROM media_assets")).isEqualTo("0");
        execute("""
            INSERT INTO media_assets
                (id, festival_id, purpose, storage_key, source_sha256, source_size_bytes,
                 source_width, source_height, master_width, master_height, normalized_format, content_type)
            VALUES ('%1$s', '%2$s', 'GOODS_IMAGE', 'goods/%2$s/01/%1$s', repeat('a', 64), 1024,
                1024, 1024, 1024, 1024, 'WEBP', 'image/webp');
            INSERT INTO goods_images (media_id, festival_id, goods_id, sort_order)
            VALUES ('%1$s', '%2$s', '%3$s', 0);
            INSERT INTO goods_image_translations (media_id, locale, alt_text)
            VALUES ('%1$s', 'ko', '검증 이미지');
            INSERT INTO festivals (id, title, timezone, created_at, updated_at)
            VALUES ('01234567-89ab-4cde-8fab-012345678905', 'Other fixture', 'Asia/Seoul',
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
            INSERT INTO goods (id, festival_id, option_mode, price_amount)
            VALUES ('01234567-89ab-4cde-8fab-012345678906',
                '01234567-89ab-4cde-8fab-012345678905', 'SINGLE', 1000);
            """.formatted(MEDIA_ID, FESTIVAL_ID, GOODS_ID));
        rejects("UPDATE goods_images SET goods_id = '01234567-89ab-4cde-8fab-012345678906'", "23503");
        rejects("UPDATE goods_images SET sort_order = 2", "23514");
        rejects("UPDATE media_assets SET storage_key = '../invalid'", "23514");
        rejects("UPDATE goods_image_translations SET alt_text = ' '", "23514");
    }

    private void verifyNoticeTemplates() throws SQLException {
        assertTemplatePlaceholder();
        assertThat(text("SELECT count(*) FROM notices WHERE template_id IS NOT NULL")).isEqualTo("0");
        assertThat(text("""
            SELECT count(*) FROM pg_constraint
            WHERE conname = 'uq_notice_templates_sort_order' AND condeferrable AND condeferred
            """)).isEqualTo("1");
        execute("""
            INSERT INTO notice_templates (id, name, sort_order) VALUES ('release-fixture', 'Fixture', 2);
            INSERT INTO notice_template_translations (template_id, locale, title, body)
            VALUES ('release-fixture', 'ko', '검증 제목', '검증 본문');
            UPDATE notices SET template_id = 'release-fixture';
            """);
        rejects("UPDATE notice_template_translations SET locale = 'fr'", "23514");
        rejects("UPDATE notice_template_translations SET title = ' '", "23514");
        rejects("UPDATE notices SET template_id = 'missing'", "23503");
        execute("DELETE FROM notice_templates WHERE id = 'release-fixture'");
        assertThat(text("SELECT count(*) FROM notices WHERE template_id IS NULL")).isEqualTo("1");
        assertThat(text("SELECT count(*) FROM notice_template_translations WHERE template_id = 'release-fixture'"))
            .isEqualTo("0");
    }

    private void verifyBoothStampSchema() throws SQLException {
        execute("""
            INSERT INTO stamp_booths (festival_revision_id, id, name, sort_order)
            VALUES ('%1$s', 'release-booth', 'Migration fixture booth', 1);
            INSERT INTO stamp_booth_tokens (festival_revision_id, booth_id, valid_date, token_sha256)
            VALUES ('%1$s', 'release-booth', NULL, repeat('b', 64));
            INSERT INTO stamp_participants (id, festival_id, token_sha256, created_at)
            VALUES
                ('%2$s', '%3$s', repeat('c', 64), '2030-10-01T11:00:00+09:00'),
                ('%4$s', '%3$s', repeat('d', 64), '2030-10-01T11:00:00+09:00'),
                ('%5$s', '%3$s', repeat('e', 64), '2030-10-01T11:00:00+09:00');
            INSERT INTO stamp_collections (participant_id, operating_date, booth_id, collected_at)
            VALUES
                ('%2$s', '2030-10-02', 'release-booth', '2030-10-02T10:00:00+09:00'),
                ('%4$s', '2030-10-01', 'release-booth', '2030-10-01T12:00:00+09:00');
            """.formatted(REVISION_ID, STAMP_PARTICIPANT_ONE, FESTIVAL_ID,
                STAMP_PARTICIPANT_TWO, STAMP_PARTICIPANT_THREE));
        rejects("""
            INSERT INTO stamp_booth_tokens (festival_revision_id, booth_id, valid_date, token_sha256)
            VALUES ('%1$s', 'release-booth', '2030-10-01', 'bad-hash')
            """.formatted(REVISION_ID), "23514");
        assertThat(text("""
            SELECT count(*) FROM pg_catalog.pg_tables
            WHERE schemaname = current_schema() AND tablename = 'stamp_participant_days'
            """)).isEqualTo("0");
    }

    private void verifyStampDailyBackfill() throws SQLException {
        assertThat(text("SELECT count(*) FROM stamp_participant_days")).isEqualTo("4");
        assertThat(text("""
            SELECT to_char(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
            FROM stamp_participant_days WHERE participant_id='%1$s' AND operating_date='2030-10-02'
            """.formatted(STAMP_PARTICIPANT_ONE))).isEqualTo("2030-10-02 01:00:00");
        assertThat(text("""
            SELECT to_char(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
            FROM stamp_participant_days WHERE participant_id='%1$s' AND operating_date='2030-10-01'
            """.formatted(STAMP_PARTICIPANT_TWO))).isEqualTo("2030-10-01 03:00:00");
        assertThat(text("""
            SELECT to_char(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
            FROM stamp_participant_days WHERE participant_id='%1$s' AND operating_date='2030-10-01'
            """.formatted(STAMP_PARTICIPANT_THREE))).isEqualTo("2030-10-01 02:00:00");
        rejects("""
            INSERT INTO stamp_participant_days (participant_id, operating_date, started_at)
            VALUES ('%1$s', '2030-10-01', CURRENT_TIMESTAMP)
            """.formatted(STAMP_PARTICIPANT_THREE), "23505");
    }

    private void verifyWelcomeDayRemoval() throws SQLException {
        assertThat(text("SELECT count(*) FROM festival_links WHERE id='release-welcome'")).isEqualTo("0");
        assertThat(text("SELECT count(*) FROM festival_link_translations WHERE link_id='release-welcome'")).isEqualTo("0");
        rejects("""
            INSERT INTO festival_links (festival_revision_id, id, kind, url, sort_order)
            VALUES ('%1$s', 'removed-welcome', 'WELCOME_DAY', 'https://example.test/welcome', 1)
            """.formatted(REVISION_ID), "23514");
    }

    private void verifyLoveLetterSchemaAndCascades() throws SQLException {
        assertLoveLetterTablesAreEmpty();
        execute("""
            INSERT INTO love_letter_settings (festival_id, opens_at, closes_at, consent_version)
            VALUES ('%1$s', '2030-10-01T00:00:00Z', '2030-10-03T15:00:00Z', 'v1');
            INSERT INTO love_letter_participants (id, festival_id, token_sha256, created_at)
            VALUES
                ('%2$s', '%1$s', repeat('a', 64), '2030-10-01T03:00:00Z'),
                ('%3$s', '%1$s', repeat('b', 64), '2030-10-01T03:00:00Z'),
                ('%4$s', '%1$s', NULL, '2030-10-01T03:00:00Z');
            INSERT INTO love_letters
                (id, festival_id, author_id, created_date, gender, name_cipher, body_cipher, contact_cipher,
                 key_version, consent_version, consent_at, created_at)
            VALUES
                ('%5$s', '%1$s', '%2$s', '2030-10-01', 'FEMALE', 'encrypted-name', 'encrypted-body',
                 'encrypted-contact', 'v1', 'v1', '2030-10-01T03:00:00Z', '2030-10-01T03:00:00Z');
            INSERT INTO love_letter_participation_days
                (participant_id, operating_date, letter_id, state, completed_at)
            VALUES ('%2$s', '2030-10-01', '%5$s', 'COMPLETED', '2030-10-01T03:00:00Z');
            INSERT INTO love_letter_exchanges (id, festival_id, receiver_id, letter_id, operating_date, created_at)
            VALUES ('%6$s', '%1$s', '%3$s', '%5$s', '2030-10-01', '2030-10-01T03:00:00Z');
            INSERT INTO love_letter_requests
                (participant_id, operating_date, idempotency_key, request_hmac, letter_id)
            VALUES ('%2$s', '2030-10-01', 'release-register', repeat('f', 64), '%5$s');
            INSERT INTO love_letter_invitations (id, participant_id, token_sha256, operating_date, created_at)
            VALUES ('%7$s', '%2$s', repeat('e', 64), '2030-10-01', '2030-10-01T03:00:00Z');
            INSERT INTO love_letter_reports (id, exchange_id, reporter_id, created_at)
            VALUES ('%8$s', '%6$s', '%3$s', '2030-10-01T03:01:00Z');
            INSERT INTO love_letters
                (id, festival_id, author_id, created_date, gender, name_cipher, body_cipher, contact_cipher,
                 key_version, consent_version, consent_at, created_at)
            VALUES
                ('%9$s', '%1$s', '%4$s', '2030-10-01', 'MALE', 'encrypted-name-2', 'encrypted-body-2',
                 'encrypted-contact-2', 'v1', 'v1', '2030-10-01T03:00:00Z', '2030-10-01T03:00:00Z');
            """.formatted(FESTIVAL_ID, LOVE_AUTHOR_ID, LOVE_RECEIVER_ID, LOVE_INVITED_ID,
                LOVE_LETTER_ID, LOVE_EXCHANGE_ID, LOVE_INVITATION_ID, LOVE_REPORT_ID, LOVE_OTHER_LETTER_ID));

        assertThat(text("SELECT count(*) FROM love_letter_settings")).isEqualTo("1");
        assertThat(text("SELECT count(*) FROM love_letter_participants")).isEqualTo("3");
        assertThat(text("SELECT count(*) FROM love_letters")).isEqualTo("2");
        assertThat(text("SELECT count(*) FROM love_letter_exchanges")).isEqualTo("1");
        assertThat(text("SELECT count(*) FROM love_letter_reports")).isEqualTo("1");
        assertThat(text("SELECT count(*) FROM love_letter_requests")).isEqualTo("1");
        assertThat(text("SELECT count(*) FROM love_letter_invitations")).isEqualTo("1");

        rejects("""
            UPDATE love_letter_settings SET opens_at=closes_at WHERE festival_id='%1$s'
            """.formatted(FESTIVAL_ID), "23514");
        rejects("""
            INSERT INTO love_letter_participants (id, festival_id, token_sha256, created_at)
            VALUES ('01234567-89ab-4cde-8fab-012345678938', '%1$s', 'bad-hash', CURRENT_TIMESTAMP)
            """.formatted(FESTIVAL_ID), "23514");
        rejects("""
            INSERT INTO love_letters
                (id, festival_id, author_id, created_date, gender, name_cipher, body_cipher, contact_cipher,
                 key_version, consent_version, consent_at, created_at)
            VALUES ('01234567-89ab-4cde-8fab-012345678939', '%1$s', '%2$s', '2030-10-02', 'OTHER',
                'name', 'body', 'contact', 'v1', 'v1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.formatted(FESTIVAL_ID, LOVE_INVITED_ID), "23514");
        rejects("""
            INSERT INTO love_letter_requests
                (participant_id, operating_date, idempotency_key, request_hmac)
            VALUES ('%1$s', '2030-10-01', 'bad-result', repeat('f', 64))
            """.formatted(LOVE_RECEIVER_ID), "23514");
        rejects("""
            INSERT INTO love_letter_requests
                (participant_id, operating_date, idempotency_key, request_hmac, letter_id)
            VALUES ('%1$s', '2030-10-01', 'release-register', repeat('f', 64), '%2$s')
            """.formatted(LOVE_AUTHOR_ID, LOVE_LETTER_ID), "23505");
        rejects("""
            INSERT INTO love_letter_reports (id, exchange_id, reporter_id, created_at)
            VALUES ('01234567-89ab-4cde-8fab-012345678942', '%1$s', '%2$s', CURRENT_TIMESTAMP)
            """.formatted(LOVE_EXCHANGE_ID, LOVE_RECEIVER_ID), "23505");
        rejects("""
            INSERT INTO love_letter_exchanges (id, festival_id, receiver_id, letter_id, operating_date, created_at)
            VALUES ('01234567-89ab-4cde-8fab-012345678940', '%1$s', '%2$s', '%3$s', '2030-10-02', CURRENT_TIMESTAMP)
            """.formatted(FESTIVAL_ID, LOVE_INVITED_ID, LOVE_LETTER_ID), "23505");
        rejects("""
            INSERT INTO love_letter_exchanges (id, festival_id, receiver_id, letter_id, operating_date, created_at)
            VALUES ('01234567-89ab-4cde-8fab-012345678941', '%1$s', '%2$s', '%3$s', '2030-10-01', CURRENT_TIMESTAMP)
            """.formatted(FESTIVAL_ID, LOVE_RECEIVER_ID, LOVE_OTHER_LETTER_ID), "23505");

        execute("DELETE FROM love_letter_participants WHERE id='%s'".formatted(LOVE_AUTHOR_ID));
        assertThat(text("SELECT count(*) FROM love_letter_participants")).isEqualTo("2");
        assertThat(text("SELECT count(*) FROM love_letters")).isEqualTo("1");
        assertThat(text("SELECT count(*) FROM love_letter_participation_days")).isEqualTo("0");
        assertThat(text("SELECT count(*) FROM love_letter_exchanges")).isEqualTo("0");
        assertThat(text("SELECT count(*) FROM love_letter_reports")).isEqualTo("0");
        assertThat(text("SELECT count(*) FROM love_letter_requests")).isEqualTo("0");
        assertThat(text("SELECT count(*) FROM love_letter_invitations")).isEqualTo("0");
    }

    private void assertLoveLetterTablesAreEmpty() throws SQLException {
        for (String table : List.of("love_letter_settings", "love_letter_participants", "love_letters",
            "love_letter_participation_days", "love_letter_exchanges", "love_letter_requests",
            "love_letter_invitations", "love_letter_reports")) {
            assertThat(text("SELECT count(*) FROM \"" + table + "\"")).as(table).isEqualTo("0");
        }
    }

    private void assertTemplatePlaceholder() throws SQLException {
        assertThat(text("SELECT count(*) FROM notice_templates")).isEqualTo("1");
        assertThat(text("""
            SELECT string_agg(locale, ',' ORDER BY locale) FROM notice_template_translations
            WHERE template_id = 'template-registration-required'
            """)).isEqualTo("en,ja,ko,zh-Hans");
    }

    private void assertHistory(int version) throws SQLException {
        Flyway migration = flyway(version);
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        assertThat(migration.info().current().getVersion().getVersion()).isEqualTo(Integer.toString(version));
        assertThat(rows("SELECT version FROM flyway_schema_history ORDER BY installed_rank"))
            .containsExactlyElementsOf(IntStream.rangeClosed(1, version).mapToObj(Integer::toString).toList());
        assertThat(text("""
            SELECT count(*) FROM flyway_schema_history
            WHERE NOT success OR checksum IS NULL OR type <> 'SQL'
            """)).isEqualTo("0");
    }

    private void assertRestartIsUnchanged() throws SQLException {
        List<String> beforeHistory = history();
        Map<String, String> beforeData = snapshot(tables(), false);
        Flyway restarted = flyway(30);
        assertThat(restarted.migrate().migrationsExecuted).isZero();
        assertThat(restarted.validateWithResult().validationSuccessful).isTrue();
        assertThat(restarted.info().pending()).isEmpty();
        assertThat(history()).isEqualTo(beforeHistory);
        assertThat(snapshot(tables(), false)).isEqualTo(beforeData);
    }

    private Flyway flyway(int target) {
        return Flyway.configure()
            .dataSource(databaseUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .placeholders(Map.of("festivalId", ""))
            .target(Integer.toString(target))
            .load();
    }

    private List<String> history() throws SQLException {
        return rows("SELECT to_jsonb(h)::text FROM flyway_schema_history h ORDER BY installed_rank");
    }

    private List<String> tables() throws SQLException {
        return rows("""
            SELECT tablename FROM pg_tables
            WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' ORDER BY tablename
            """);
    }

    private Map<String, String> snapshot(List<String> tables, boolean legacyNoticeShape) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : tables) {
            String row = legacyNoticeShape && table.equals("notices")
                ? "(to_jsonb(t) - 'template_id')" : "to_jsonb(t)";
            result.put(table, text("SELECT coalesce(jsonb_agg(" + row + " ORDER BY " + row
                + "::text), '[]'::jsonb)::text FROM \"" + table.replace("\"", "\"\"") + "\" t"));
        }
        return result;
    }

    private void rejects(String sql, String state) {
        assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class)
            .extracting(exception -> ((SQLException) exception).getSQLState()).isEqualTo(state);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private String text(String sql) throws SQLException {
        return rows(sql).getFirst();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(databaseUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
