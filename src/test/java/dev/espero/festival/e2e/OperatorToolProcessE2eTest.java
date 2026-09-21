package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.AccountSettingsCliApplication;
import dev.espero.festival.CatalogCliApplication;
import dev.espero.festival.CatalogExportService;
import dev.espero.festival.preflight.DatabasePreflightApplication;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs non-web operator entry points in separate JVMs against only a fresh
 * Testcontainers database. This verifies process exit behavior and the actual
 * catalog/account transaction boundaries used for a release.
 */
@Testcontainers
class OperatorToolProcessE2eTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final String READ_ONLY_ROLE_PASSWORD = "operator-read-only-password";
    private static final Pattern PRINTED_REVISION = Pattern.compile("(?:draft|exported|validated|published|rolled back to) revision: "
        + "([0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    private String databaseUrl;

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @BeforeEach
    void migrateFreshDatabase() throws SQLException {
        String database = "operator_tool_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        databaseUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        Flyway.configure()
            .dataSource(databaseUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
            .placeholders(Map.of("festivalId", FESTIVAL_ID.toString()))
            .load()
            .migrate();
    }

    @Test
    void catalogCliUsesBaselineGuardsAndRollsBackThroughItsRealMain() throws Exception {
        UUID initialRevision = currentPublishedRevision();
        Path manifest = candidateManifest();

        UUID firstDraft = printedRevision(success(catalog(
            "import", "--manifest=" + manifest, "--festival-id=" + FESTIVAL_ID,
            "--baseline-revision=" + initialRevision, "--actor=operator-tool-e2e"
        )));
        UUID secondDraft = printedRevision(success(catalog(
            "import", "--manifest=" + manifest, "--festival-id=" + FESTIVAL_ID,
            "--baseline-revision=" + initialRevision, "--actor=operator-tool-e2e"
        )));

        ProcessResult validate = success(catalog("validate", "--revision=" + firstDraft, "--actor=operator-tool-e2e"));
        assertThat(validate.output()).contains("validated revision: " + firstDraft);
        success(catalog("publish", "--revision=" + firstDraft, "--actor=operator-tool-e2e"));
        assertThat(currentPublishedRevision()).isEqualTo(firstDraft);
        assertThat(revisionState(secondDraft)).isEqualTo("draft");
        assertThat(auditCount(secondDraft, "PUBLISH")).isZero();

        ProcessResult stalePublish = catalog("publish", "--revision=" + secondDraft, "--actor=operator-tool-e2e");
        assertFailure(stalePublish, "catalog-cli: BASE_REVISION_CONFLICT");
        assertThat(currentPublishedRevision()).isEqualTo(firstDraft);
        assertThat(revisionState(secondDraft)).isEqualTo("draft");
        assertThat(auditCount(secondDraft, "PUBLISH")).isZero();

        Path exported = temporaryDirectory.resolve("catalog-export.json");
        ProcessResult export = success(catalog("export", "--revision=" + firstDraft, "--out=" + exported));
        assertThat(export.output()).contains("exported revision: " + firstDraft);
        assertThat(Files.readString(exported)).contains("\"festivalId\"", "\"spaces\"", "\"maps\"", "\"performances\"");

        UUID replacement = printedRevision(success(catalog(
            "import", "--manifest=" + manifest, "--festival-id=" + FESTIVAL_ID,
            "--baseline-revision=" + firstDraft, "--actor=operator-tool-e2e"
        )));
        success(catalog("publish", "--revision=" + replacement, "--actor=operator-tool-e2e"));
        assertThat(currentPublishedRevision()).isEqualTo(replacement);
        assertThat(revisionState(firstDraft)).isEqualTo("archived");

        UUID rollback = printedRevision(success(catalog(
            "rollback", "--revision=" + firstDraft, "--expected-current=" + replacement, "--actor=operator-tool-e2e"
        )));
        assertThat(currentPublishedRevision()).isEqualTo(rollback);
        assertThat(revisionState(rollback)).isEqualTo("published");
        assertThat(baseRevision(rollback)).isEqualTo(replacement);
        assertThat(auditCount(rollback, "ROLLBACK")).isOne();

        ProcessResult staleRollback = catalog(
            "rollback", "--revision=" + firstDraft, "--expected-current=" + replacement, "--actor=operator-tool-e2e"
        );
        assertFailure(staleRollback, "catalog-cli: BASE_REVISION_CONFLICT");
        assertThat(currentPublishedRevision()).isEqualTo(rollback);

        ProcessResult malformedRevision = catalog("validate", "--revision=not-a-uuid", "--actor=operator-tool-e2e");
        assertFailure(malformedRevision, "catalog-cli: Option --revision must be a valid UUID.");
        assertThat(malformedRevision.output()).doesNotContain("Exception in thread", databaseUrl, POSTGRES.getPassword());

        String rejectedDatasource = "jdbc:unsupported:must-not-leak";
        ProcessResult inheritedHikariOverride = catalogWithInheritedEnvironment(Map.of(
            "SPRING_DATASOURCE_HIKARI_JDBC_URL", rejectedDatasource,
            "SPRING_DATASOURCE_HIKARI_USERNAME", "must-not-leak-user",
            "SPRING_DATASOURCE_HIKARI_PASSWORD", "must-not-leak-password"
        ), "validate", "--revision=" + rollback, "--actor=operator-tool-e2e");
        assertThat(success(inheritedHikariOverride).output()).doesNotContain(
            rejectedDatasource, "must-not-leak-user", "must-not-leak-password"
        );

        ProcessResult unavailable = catalogWithRuntimeEnvironment(Map.of(
            "SPRING_DATASOURCE_HIKARI_JDBC_URL", rejectedDatasource,
            "LOGGING_LEVEL_COM_ZAXXER_HIKARI", "DEBUG",
            "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK", "DEBUG"
        ), "validate", "--revision=" + rollback);
        assertFailure(unavailable, "catalog-cli: CATALOG_CLI_UNAVAILABLE");
        assertThat(unavailable.output()).doesNotContain(
            "Application run failed", "Exception in thread", rejectedDatasource, databaseUrl, POSTGRES.getPassword()
        );
    }

    @Test
    void catalogCliRejectsCorruptDraftAndRecoversWithAValidReplacementThroughSeparateProcesses() throws Exception {
        UUID initialRevision = currentPublishedRevision();
        Path manifest = candidateManifest();

        UUID corruptDraft = printedRevision(success(catalog(
            "import", "--manifest=" + manifest, "--festival-id=" + FESTIVAL_ID,
            "--baseline-revision=" + initialRevision, "--actor=operator-tool-e2e"
        )));
        deletePerformanceTranslations(corruptDraft);

        ProcessResult rejected = catalog("publish", "--revision=" + corruptDraft, "--actor=operator-tool-e2e");
        assertFailure(rejected, "catalog-cli:");
        assertThat(rejected.output()).doesNotContain("Exception in thread", databaseUrl, POSTGRES.getPassword());
        assertThat(currentPublishedRevision()).isEqualTo(initialRevision);
        assertThat(revisionState(corruptDraft)).isEqualTo("draft");
        assertThat(auditCount(corruptDraft, "PUBLISH")).isZero();

        UUID replacement = printedRevision(success(catalog(
            "import", "--manifest=" + manifest, "--festival-id=" + FESTIVAL_ID,
            "--baseline-revision=" + initialRevision, "--actor=operator-tool-e2e"
        )));
        success(catalog("publish", "--revision=" + replacement, "--actor=operator-tool-e2e"));
        assertThat(currentPublishedRevision()).isEqualTo(replacement);
        assertThat(revisionState(corruptDraft)).isEqualTo("draft");
    }

    @Test
    void catalogCliRoundTripsEveryManifestSectionIncludingHistoricalAssetsAndUnfilteredPins() throws Exception {
        UUID baseline = currentPublishedRevision();
        Path input = candidateManifest();
        ObjectNode candidate = (ObjectNode) json.readTree(Files.readString(input));
        ArrayNode assets = (ArrayNode) candidate.path("mapAssets");
        ObjectNode historicalAsset = ((ObjectNode) assets.get(0)).deepCopy();
        historicalAsset.put("version", "roundtrip-history-v1");
        historicalAsset.put("imageUrl", "/assets/maps/roundtrip-history-v1.png");
        assets.add(historicalAsset);
        candidate.putArray("festivalTitleTranslations").addObject()
            .put("locale", "en").put("title", "[TEST] Round-trip festival");
        candidate.putArray("mapAssetTranslations").addObject()
            .put("mapId", historicalAsset.path("mapId").asText())
            .put("version", historicalAsset.path("version").asText())
            .put("locale", "en").put("imageAlt", "[TEST] Historical map");
        // Null PLACE filter groups are valid: these pins are outside the optional design filters.
        ObjectNode unfilteredPin = null;
        for (JsonNode pin : candidate.path("mapPins")) {
            if (pin.path("placeId").isTextual()) {
                unfilteredPin = (ObjectNode) pin;
                unfilteredPin.putNull("filterGroup");
                break;
            }
        }
        assertThat(unfilteredPin).isNotNull();
        Files.writeString(input, json.writeValueAsString(candidate));
        UUID source = importCandidate(input, baseline);
        Path firstExport = temporaryDirectory.resolve("first-export.json");
        Map<String, Long> beforeExport = catalogRowCounts();
        ProcessResult exported = success(catalog("export", "--revision=" + source, "--out=" + firstExport));
        assertThat(exported.output()).doesNotContain("finding:");
        assertThat(catalogRowCounts()).isEqualTo(beforeExport);

        UUID copy = printedRevision(success(catalog("import", "--manifest=" + firstExport)));
        assertThat(copy).isNotEqualTo(source);
        assertThat(baseRevision(copy)).isEqualTo(baseline);
        success(catalog("validate", "--revision=" + copy));
        assertThat(auditCount(copy, "VALIDATE")).isOne();
        Path secondExport = temporaryDirectory.resolve("second-export.json");
        success(catalog("export", "--revision=" + copy, "--out=" + secondExport));

        ObjectNode original = (ObjectNode) json.readTree(Files.readString(firstExport));
        ObjectNode roundTripped = (ObjectNode) json.readTree(Files.readString(secondExport));
        // Export excludes generated row IDs/audit timestamps. Compare every remaining field;
        // normalize only top-level row order, preserving ordered instructions inside each row.
        assertThat(canonicalManifest(roundTripped)).isEqualTo(canonicalManifest(original));
        assertThat(original.path("mapAssets").size()).isEqualTo(assets.size());
        assertThat(original.path("festivalTitleTranslations").size()).isOne();
        assertThat(original.path("mapAssetTranslations").size()).isOne();
        for (String section : List.of("spaces", "places", "maps", "mapPins", "artists", "performances",
            "performanceArtists", "prohibitedItems", "festivalLinks")) {
            assertThat(original.path(section).size()).as(section + " is exercised").isPositive();
        }
        assertThat(original.path("ticketGuide").isObject()).isTrue();
        assertThat(original.path("stampGuide").isObject()).isTrue();
        assertThat(currentPublishedRevision()).isEqualTo(baseline);
        success(catalog("publish", "--revision=" + copy));
        assertThat(currentPublishedRevision()).isEqualTo(copy);
        assertThat(revisionState(source)).isEqualTo("draft");
    }

    @ParameterizedTest
    @ValueSource(strings = {"festival_start_date", "festival_end_date", "daily_transfer_open_time",
        "daily_transfer_close_time", "daily_pickup_open_time", "daily_pickup_close_time"})
    void catalogCliBlocksEveryPartialLegacyTicketScheduleAndPublishesOnlyAfterCorrection(String missingColumn)
        throws Exception {
        UUID baseline = currentPublishedRevision();
        Path input = candidateManifest();
        UUID legacy = importCandidate(input, baseline);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            // Identifier comes only from the explicit test parameter list above.
            "UPDATE ticket_guide_revisions SET " + missingColumn + " = NULL WHERE festival_revision_id = ?"
        )) {
            statement.setObject(1, legacy);
            assertThat(statement.executeUpdate()).isOne();
        }
        Map<String, Long> before = catalogRowCounts();
        long draftsBefore = draftCount();
        Path exported = temporaryDirectory.resolve("legacy-ticket.json");
        ProcessResult export = success(catalog("export", "--revision=" + legacy, "--out=" + exported));
        assertThat(export.output()).contains("finding: LEGACY_TICKET_SCHEDULE_UNCONFIGURED");
        assertThat(catalogRowCounts()).isEqualTo(before);

        for (String[] command : List.of(
            new String[] {"import", "--manifest=" + exported},
            new String[] {"validate", "--revision=" + legacy},
            new String[] {"publish", "--revision=" + legacy}
        )) {
            ProcessResult rejected = catalog(command);
            assertFailure(rejected, CatalogExportService.LEGACY_TICKET_SCHEDULE_UNCONFIGURED);
            assertThat(rejected.output()).doesNotContain(databaseUrl, POSTGRES.getPassword(), "Exception in thread");
            assertThat(currentPublishedRevision()).isEqualTo(baseline);
            assertThat(revisionState(legacy)).isEqualTo("draft");
            assertThat(draftCount()).isEqualTo(draftsBefore);
            assertThat(catalogRowCounts()).isEqualTo(before);
        }

        ObjectNode correction = (ObjectNode) json.readTree(Files.readString(exported));
        correction.set("ticketGuide", json.readTree(Files.readString(input)).path("ticketGuide"));
        Files.writeString(exported, json.writeValueAsString(correction));
        UUID corrected = printedRevision(success(catalog("import", "--manifest=" + exported)));
        success(catalog("validate", "--revision=" + corrected));
        success(catalog("publish", "--revision=" + corrected));
        assertThat(currentPublishedRevision()).isEqualTo(corrected);
        assertThat(revisionState(legacy)).isEqualTo("draft");
        assertThat(auditCount(legacy, "VALIDATE")).isZero();
        assertThat(auditCount(legacy, "PUBLISH")).isZero();
        assertThat(auditCount(corrected, "IMPORT")).isOne();
        assertThat(auditCount(corrected, "VALIDATE")).isOne();
        assertThat(auditCount(corrected, "PUBLISH")).isOne();
    }

    private ObjectNode canonicalManifest(ObjectNode manifest) {
        ObjectNode canonical = manifest.deepCopy();
        for (String name : manifest.propertyNames()) {
            if (manifest.path(name).isArray()) {
                List<JsonNode> rows = new ArrayList<>();
                manifest.path(name).forEach(rows::add);
                rows.sort(java.util.Comparator.comparing(JsonNode::toString));
                ArrayNode sorted = canonical.putArray(name);
                rows.forEach(sorted::add);
            }
        }
        return canonical;
    }

    @Test
    void catalogCliCannotImportWithAReadOnlyRoleAndDoesNotCreateDraftOrAuditRows() throws Exception {
        String role = createReadOnlyRole("catalog_readonly");
        UUID initialRevision = currentPublishedRevision();
        Path manifest = candidateManifest();

        ProcessResult denied = catalogWithRuntimeEnvironment(Map.of(
            "SPRING_DATASOURCE_USERNAME", role,
            "SPRING_DATASOURCE_PASSWORD", READ_ONLY_ROLE_PASSWORD
        ), "import", "--manifest=" + manifest, "--festival-id=" + FESTIVAL_ID,
            "--baseline-revision=" + initialRevision, "--actor=operator-tool-e2e");

        assertFailure(denied, "catalog-cli: CATALOG_CLI_UNAVAILABLE");
        assertThat(denied.output()).doesNotContain(
            "Exception in thread", databaseUrl, POSTGRES.getPassword(), READ_ONLY_ROLE_PASSWORD
        );
        assertThat(currentPublishedRevision()).isEqualTo(initialRevision);
        assertThat(draftCount()).isZero();
        assertThat(totalCatalogAuditCount()).isZero();
    }

    @Test
    void accountSettingsCliKeepsDryRunsSafeAndChangesVersionedHistoryThroughItsRealMain() throws Exception {
        Path input = temporaryDirectory.resolve("account-input.json");
        String accountNumber = "12345678901234";
        Files.writeString(input, """
            {"bankName":"Test Bank","accountNumber":"%s","accountHolder":"Test Holder","transferLinkUrl":null}
            """.formatted(accountNumber), StandardCharsets.UTF_8);

        ProcessResult dryRun = success(account("set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=0", "--input-file=" + input, "--last-four=1234"));
        assertThat(dryRun.output()).contains("mode=DRY_RUN", "resultVersion=1", "changed=true")
            .doesNotContain(accountNumber, "Test Bank", "Test Holder", databaseUrl, POSTGRES.getPassword());
        assertThat(accountCount()).isZero();
        assertThat(accountHistoryCount()).isZero();

        ProcessResult applied = success(account("set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=0", "--input-file=" + input, "--last-four=1234", "--confirm",
            "--actor=operator-tool-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-1"));
        assertThat(applied.output()).contains("mode=APPLIED", "resultVersion=1", "state=CONFIGURED", "accountLastFour=1234")
            .doesNotContain(accountNumber, "Test Bank", "Test Holder", databaseUrl, POSTGRES.getPassword());
        assertThat(accountState()).isEqualTo("CONFIGURED");
        assertThat(accountVersion()).isOne();
        assertThat(accountHistoryActions()).containsExactly("SET");

        ProcessResult wrongLastFour = account("set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=1", "--input-file=" + input, "--last-four=9999", "--confirm",
            "--actor=operator-tool-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-2");
        assertFailure(wrongLastFour, "account-settings-cli: ACCOUNT_LAST_FOUR_MISMATCH");
        assertThat(wrongLastFour.output()).doesNotContain(accountNumber, "Test Bank", "Test Holder", databaseUrl, POSTGRES.getPassword());
        assertThat(accountVersion()).isOne();
        assertThat(accountHistoryCount()).isOne();

        ProcessResult cleared = success(account("clear", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=1", "--confirm", "--actor=operator-tool-e2e", "--reason=release-verification",
            "--evidence-id=OPS-E2E-3"));
        assertThat(cleared.output()).contains("resultVersion=2", "state=UNCONFIGURED");
        assertThat(accountState()).isEqualTo("UNCONFIGURED");
        assertThat(accountVersion()).isEqualTo(2L);

        ProcessResult restored = success(account("restore-version", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=2", "--source-version=1", "--last-four=1234", "--confirm",
            "--actor=operator-tool-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-4"));
        assertThat(restored.output()).contains("resultVersion=3", "state=CONFIGURED", "accountLastFour=1234")
            .doesNotContain(accountNumber, "Test Bank", "Test Holder", databaseUrl, POSTGRES.getPassword());
        assertThat(accountState()).isEqualTo("CONFIGURED");
        assertThat(accountVersion()).isEqualTo(3L);
        assertThat(accountHistoryActions()).containsExactly("SET", "CLEAR", "RESTORE");
        assertThat(accountHistoryActorCount("operator-tool-e2e")).isEqualTo(3L);

        ProcessResult noChange = success(account("set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=3", "--input-file=" + input, "--last-four=1234", "--confirm",
            "--actor=operator-tool-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-5"));
        assertThat(noChange.output()).contains("mode=APPLIED", "resultVersion=3", "changed=false")
            .doesNotContain(accountNumber, "Test Bank", "Test Holder", databaseUrl, POSTGRES.getPassword());
        assertThat(accountHistoryActions()).containsExactly("SET", "CLEAR", "RESTORE");

        String rejectedDatasource = "jdbc:unsupported:account-must-not-leak";
        ProcessResult unavailable = accountWithRuntimeEnvironment(Map.of(
            "SPRING_DATASOURCE_HIKARI_JDBC_URL", rejectedDatasource,
            "LOGGING_LEVEL_COM_ZAXXER_HIKARI", "DEBUG",
            "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK", "DEBUG"
        ), "clear", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET", "--expected-version=3", "--confirm",
            "--actor=operator-tool-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-6");
        assertFailure(unavailable, "account-settings-cli: ACCOUNT_CLI_UNAVAILABLE");
        assertThat(unavailable.output()).doesNotContain(
            "Application run failed", "Exception in thread", rejectedDatasource, databaseUrl, POSTGRES.getPassword()
        );
    }

    @Test
    void accountSettingsCliRejectsUnknownInputAndStaleExpectedVersionWithoutWriting() throws Exception {
        Path invalid = temporaryDirectory.resolve("invalid-account-input.json");
        Files.writeString(invalid, """
            {"bankName":"Test Bank","accountNumber":"12345678901234","accountHolder":"Test Holder",
             "transferLinkUrl":null,"unexpected":"must-be-rejected"}
            """, StandardCharsets.UTF_8);

        ProcessResult invalidInput = account("set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=0", "--input-file=" + invalid, "--last-four=1234", "--confirm",
            "--actor=operator-tool-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-7");
        assertFailure(invalidInput, "account-settings-cli: ACCOUNT_CLI_UNAVAILABLE");
        assertThat(accountCount()).isZero();
        assertThat(accountHistoryCount()).isZero();

        Path input = temporaryDirectory.resolve("valid-account-input.json");
        Files.writeString(input, """
            {"bankName":"Test Bank","accountNumber":"12345678901234","accountHolder":"Test Holder",
             "transferLinkUrl":null}
            """, StandardCharsets.UTF_8);
        success(account("set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=0", "--input-file=" + input, "--last-four=1234", "--confirm",
            "--actor=operator-tool-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-8"));

        ProcessResult stale = account("clear", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=0", "--confirm", "--actor=operator-tool-e2e",
            "--reason=release-verification", "--evidence-id=OPS-E2E-9");
        assertFailure(stale, "account-settings-cli: ACCOUNT_EXPECTED_VERSION_MISMATCH");
        assertThat(accountState()).isEqualTo("CONFIGURED");
        assertThat(accountVersion()).isOne();
        assertThat(accountHistoryCount()).isOne();
    }

    @Test
    void accountSettingsCliCannotWriteThroughAReadOnlyRole() throws Exception {
        String role = createReadOnlyRole("account_readonly");
        Path input = temporaryDirectory.resolve("account-input.json");
        Files.writeString(input, """
            {"bankName":"Test Bank","accountNumber":"12345678901234","accountHolder":"Test Holder",
             "transferLinkUrl":null}
            """, StandardCharsets.UTF_8);

        ProcessResult denied = accountWithRuntimeEnvironment(Map.of(
            "SPRING_DATASOURCE_USERNAME", role,
            "SPRING_DATASOURCE_PASSWORD", READ_ONLY_ROLE_PASSWORD
        ), "set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET", "--expected-version=0",
            "--input-file=" + input, "--last-four=1234", "--confirm", "--actor=operator-tool-e2e",
            "--reason=release-verification", "--evidence-id=OPS-E2E-10");

        assertFailure(denied, "account-settings-cli: ACCOUNT_CLI_UNAVAILABLE");
        assertThat(denied.output()).doesNotContain(
            "Exception in thread", databaseUrl, POSTGRES.getPassword(), READ_ONLY_ROLE_PASSWORD
        );
        assertThat(accountCount()).isZero();
        assertThat(accountHistoryCount()).isZero();
    }

    @Test
    void databasePreflightStandaloneMainStopsOnExistingCatalogAndRedactsConfigurationFailures() throws Exception {
        ProcessResult existing = preflight(Map.of(
            "PREFLIGHT_FESTIVAL_ID", FESTIVAL_ID.toString(),
            "SPRING_PROFILES_ACTIVE", "db,catalog-cli",
            "SPRING_FLYWAY_ENABLED", "true"
        ));
        assertThat(existing.exitCode()).isEqualTo(2);
        assertThat(existing.output()).contains(
            "mode=READ_ONLY_DATABASE_PREFLIGHT", "status=STOP_AND_REVIEW", "mutationAuthorized=false",
            "finding=EXISTING_FESTIVAL_OR_CATALOG_DATA", "transactionReadOnly=on"
        ).doesNotContain("Spring Boot", "Flyway Community", "Exception", databaseUrl);
        assertThat(currentPublishedRevision()).isNotNull();

        String secretUrl = "jdbc:postgresql://secret-user:secret-password@db.invalid:5432/festival";
        ProcessResult invalid = preflight(Map.of(
            "PREFLIGHT_DATASOURCE_URL", secretUrl,
            "PREFLIGHT_DATASOURCE_USERNAME", "secret-user",
            "PREFLIGHT_DATASOURCE_PASSWORD", "secret-password",
            "PREFLIGHT_SCHEMA", "public"
        ));
        assertThat(invalid.exitCode()).isEqualTo(2);
        assertThat(invalid.output()).contains("status=STOP_AND_REVIEW", "finding=CONFIGURATION_INVALID")
            .doesNotContain(secretUrl, "secret-user", "secret-password");
    }

    @ParameterizedTest(name = "{0} rolls back if its final audit write fails")
    @ValueSource(strings = {"IMPORT", "PUBLISH", "ROLLBACK"})
    void catalogCliRollsBackLateAuditFailureAndCanRetry(String operation) throws Exception {
        UUID initial = currentPublishedRevision();
        Path manifest = candidateManifest();
        if (operation.equals("ROLLBACK")) {
            // Restore a complete operator-published catalog, not the sparse migration fixture.
            initial = importCandidate(manifest, initial);
            success(catalog("publish", "--revision=" + initial));
        }
        UUID draft = null;
        UUID published = initial;
        if (!operation.equals("IMPORT")) {
            draft = importCandidate(manifest, initial);
        }
        if (operation.equals("ROLLBACK")) {
            success(catalog("publish", "--revision=" + draft));
            published = draft;
        }
        String[] command = switch (operation) {
            case "IMPORT" -> new String[] {"import", "--manifest=" + manifest,
                "--festival-id=" + FESTIVAL_ID, "--baseline-revision=" + initial};
            case "PUBLISH" -> new String[] {"publish", "--revision=" + draft};
            default -> new String[] {"rollback", "--revision=" + initial, "--expected-current=" + published};
        };
        Map<String, Long> rowsBefore = catalogRowCounts();
        String stateBefore = revisionState(initial);
        // In rollback, fail PUBLISH after copying all content and inserting the ROLLBACK audit.
        String failingAction = operation.equals("IMPORT") ? "IMPORT" : "PUBLISH";
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE FUNCTION e2e_fail_catalog_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.action = '%s' THEN RAISE EXCEPTION 'e2e late audit failure'; END IF;
                  RETURN NEW;
                END; $$
                """.formatted(failingAction));
            statement.execute("CREATE TRIGGER e2e_fail_audit BEFORE INSERT ON catalog_revision_audit "
                + "FOR EACH ROW EXECUTE FUNCTION e2e_fail_catalog_audit()");
        }
        try {
            ProcessResult failed = catalog(command);
            assertFailure(failed, "catalog-cli: CATALOG_CLI_UNAVAILABLE");
            assertThat(failed.output()).doesNotContain("e2e late audit failure", databaseUrl, POSTGRES.getPassword());
            assertThat(currentPublishedRevision()).isEqualTo(published);
            assertThat(revisionState(initial)).isEqualTo(stateBefore);
            if (operation.equals("PUBLISH")) {
                assertThat(revisionState(draft)).isEqualTo("draft");
            }
            assertThat(catalogRowCounts()).isEqualTo(rowsBefore);
        } finally {
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER e2e_fail_audit ON catalog_revision_audit");
                statement.execute("DROP FUNCTION e2e_fail_catalog_audit()");
            }
        }
        UUID recovered = printedRevision(success(catalog(command)));
        if (operation.equals("IMPORT")) {
            assertThat(currentPublishedRevision()).isEqualTo(published);
            assertThat(revisionState(recovered)).isEqualTo("draft");
            assertThat(auditCount(recovered, "IMPORT")).isOne();
        } else {
            assertThat(currentPublishedRevision()).isEqualTo(recovered);
            assertThat(auditCount(recovered, "PUBLISH")).isOne();
            if (operation.equals("ROLLBACK")) {
                assertThat(auditCount(recovered, "ROLLBACK")).isOne();
                assertThat(baseRevision(recovered)).isEqualTo(published);
            }
        }
    }

    @ParameterizedTest(name = "concurrent publish versus rollback={0} serializes at the festival lock")
    @ValueSource(booleans = {false, true})
    void catalogCliSerializesCompetingProcessesWithoutLosingTheWinner(boolean competeWithRollback) throws Exception {
        UUID initial = currentPublishedRevision();
        Path manifest = candidateManifest();
        UUID source = initial;
        if (competeWithRollback) {
            source = importCandidate(manifest, initial);
            success(catalog("publish", "--revision=" + source));
        }
        UUID baseline = importCandidate(manifest, source);
        success(catalog("publish", "--revision=" + baseline));
        UUID first = importCandidate(manifest, baseline);
        UUID second = competeWithRollback ? source : importCandidate(manifest, baseline);
        long auditBefore = totalCatalogAuditCount();
        long revisionsBefore = accountScalar("SELECT count(*) FROM festival_revisions");
        FutureTask<ProcessResult> firstRun = new FutureTask<>(
            () -> catalog("publish", "--revision=" + first));
        FutureTask<ProcessResult> secondRun = new FutureTask<>(() -> competeWithRollback
            ? catalog("rollback", "--revision=" + second, "--expected-current=" + baseline)
            : catalog("publish", "--revision=" + second));
        try {
            try (Connection blocker = connection()) {
                blocker.setAutoCommit(false);
                try (PreparedStatement lock = blocker.prepareStatement("SELECT id FROM festivals WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, FESTIVAL_ID);
                    lock.executeQuery().close();
                }
                Thread.ofVirtual().start(firstRun);
                Thread.ofVirtual().start(secondRun);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    long waiting = 0;
                    while (waiting < 2 && System.nanoTime() < deadline) {
                        waiting = accountScalar("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event_type = 'Lock'
                              AND query LIKE '%FROM festivals WHERE id =%FOR UPDATE%'
                            """);
                        if (waiting < 2) Thread.sleep(50);
                    }
                    assertThat(waiting).as("both real CLI processes reach the locked festival row").isEqualTo(2);
                } finally {
                    blocker.rollback();
                }
            }
            ProcessResult firstResult = firstRun.get(30, TimeUnit.SECONDS);
            ProcessResult secondResult = secondRun.get(30, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.exitCode(), secondResult.exitCode())).containsExactlyInAnyOrder(0, 1);
            ProcessResult winner = firstResult.exitCode() == 0 ? firstResult : secondResult;
            ProcessResult loser = firstResult.exitCode() == 1 ? firstResult : secondResult;
            assertFailure(loser, "catalog-cli: BASE_REVISION_CONFLICT");
            UUID winnerId = printedRevision(winner);
            assertThat(currentPublishedRevision()).isEqualTo(winnerId);
            assertThat(revisionState(baseline)).isEqualTo("archived");
            assertThat(accountScalar("SELECT count(*) FROM festival_revisions WHERE state = 'published' "
                + "AND festival_id = ?", FESTIVAL_ID)).isOne();
            assertThat(auditCount(winnerId, "PUBLISH")).isOne();
            boolean rollbackWon = competeWithRollback && secondResult.exitCode() == 0;
            assertThat(totalCatalogAuditCount()).isEqualTo(auditBefore + (rollbackWon ? 2 : 1));
            assertThat(accountScalar("SELECT count(*) FROM festival_revisions"))
                .isEqualTo(revisionsBefore + (rollbackWon ? 1 : 0));
            if (firstResult.exitCode() != 0) assertThat(revisionState(first)).isEqualTo("draft");
            if (!competeWithRollback && secondResult.exitCode() != 0) {
                assertThat(revisionState(second)).isEqualTo("draft");
            }
        } finally {
            firstRun.cancel(true);
            secondRun.cancel(true);
        }
    }

    private UUID importCandidate(Path manifest, UUID baseline) throws Exception {
        return printedRevision(success(catalog("import", "--manifest=" + manifest,
            "--festival-id=" + FESTIVAL_ID, "--baseline-revision=" + baseline)));
    }

    private Map<String, Long> catalogRowCounts() throws SQLException {
        Map<String, Long> counts = new java.util.TreeMap<>();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery("""
                 SELECT DISTINCT table_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name = 'festival_revision_id'
                 UNION SELECT 'festival_revisions' UNION SELECT 'catalog_revision_audit'
                 """)) {
            while (tables.next()) {
                String table = tables.getString(1);
                // Table identifiers come only from the fresh container's migration-created schema.
                counts.put(table, accountScalar("SELECT count(*) FROM \"" + table + "\""));
            }
        }
        return counts;
    }

    private void deletePerformanceTranslations(UUID revisionId) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM performance_translations WHERE festival_revision_id = ?"
        )) {
            statement.setObject(1, revisionId);
            assertThat(statement.executeUpdate()).isGreaterThan(0);
        }
    }

    private String createReadOnlyRole(String prefix) throws SQLException {
        String role = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + READ_ONLY_ROLE_PASSWORD + "'");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + role);
        }
        return role;
    }

    private long draftCount() throws SQLException {
        return accountScalar("SELECT count(*) FROM festival_revisions WHERE festival_id = ? AND state = 'draft'", FESTIVAL_ID);
    }

    private long totalCatalogAuditCount() throws SQLException {
        return accountScalar("SELECT count(*) FROM catalog_revision_audit");
    }

    private ProcessResult preflight(Map<String, String> overrides) throws Exception {
        Path classes = Path.of(DatabasePreflightApplication.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        Path driver = Path.of(Class.forName("org.postgresql.Driver").getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        List<String> command = List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes + File.pathSeparator + driver,
            DatabasePreflightApplication.class.getName()
        );
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(temporaryDirectory.toFile())
            .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        copyOsEnvironment(inherited, environment);
        environment.put("PREFLIGHT_DATASOURCE_URL", databaseUrl);
        environment.put("PREFLIGHT_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("PREFLIGHT_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        environment.put("PREFLIGHT_SCHEMA", "public");
        environment.putAll(overrides);

        Process process = builder.start();
        FutureTask<String> output = new FutureTask<>(
            () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        );
        Thread.ofVirtual().start(output);
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("preflight process must terminate").isTrue();
            return new ProcessResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Path candidateManifest() throws IOException {
        Path source = Path.of("dev", "catalog", "frontend-mock-catalog.json").toAbsolutePath();
        Path copy = temporaryDirectory.resolve("frontend-mock-catalog.json");
        return Files.copy(source, copy);
    }

    private ProcessResult catalog(String... arguments) throws Exception {
        return run(CatalogCliApplication.class, Map.of(), Map.of(), arguments);
    }

    private ProcessResult catalogWithInheritedEnvironment(Map<String, String> inherited, String... arguments) throws Exception {
        return run(CatalogCliApplication.class, inherited, Map.of(), arguments);
    }

    private ProcessResult catalogWithRuntimeEnvironment(Map<String, String> runtime, String... arguments) throws Exception {
        return run(CatalogCliApplication.class, Map.of(), runtime, arguments);
    }

    private ProcessResult account(String... arguments) throws Exception {
        return run(AccountSettingsCliApplication.class, Map.of(), Map.of(), arguments);
    }

    private ProcessResult accountWithRuntimeEnvironment(Map<String, String> runtime, String... arguments) throws Exception {
        return run(AccountSettingsCliApplication.class, Map.of(), runtime, arguments);
    }

    private ProcessResult run(
        Class<?> application,
        Map<String, String> inheritedOverrides,
        Map<String, String> runtimeOverrides,
        String... arguments
    ) throws Exception {
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dspring.main.banner-mode=off",
            "-Dspring.jmx.enabled=false",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            application.getName()
        ));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(temporaryDirectory.toFile())
            .redirectErrorStream(true);
        Map<String, String> inherited = new HashMap<>(builder.environment());
        inherited.putAll(inheritedOverrides);
        Map<String, String> environment = builder.environment();
        // Never let a developer shell's SPRING_* or Hikari settings redirect
        // an E2E child to a shared database.
        environment.clear();
        copyOsEnvironment(inherited, environment);
        environment.putAll(childEnvironment());
        environment.putAll(runtimeOverrides);

        Process process = builder.start();
        FutureTask<String> output = new FutureTask<>(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(output);
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("operator process must terminate").isTrue();
            return new ProcessResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Map<String, String> childEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("SPRING_DATASOURCE_URL", databaseUrl);
        environment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        // Both applications structurally exclude Flyway; keeping this true
        // protects against a regression that only shows up in an operator shell.
        environment.put("SPRING_FLYWAY_ENABLED", "true");
        environment.put("FESTIVAL_ID", FESTIVAL_ID.toString());
        environment.put("SPRING_MAIN_BANNER_MODE", "off");
        environment.put("LOGGING_LEVEL_ROOT", "OFF");
        return environment;
    }

    private void copyOsEnvironment(Map<String, String> source, Map<String, String> target) {
        for (String name : List.of(
            "ComSpec", "HOME", "HOMEDRIVE", "HOMEPATH", "LANG", "LC_ALL", "LOCALAPPDATA", "PATH", "PATHEXT",
            "Path", "SystemRoot", "TEMP", "TMP", "TMPDIR", "USERPROFILE", "WINDIR"
        )) {
            String value = source.get(name);
            if (value != null) {
                target.put(name, value);
            }
        }
    }

    private ProcessResult success(ProcessResult result) {
        assertThat(result.exitCode()).isZero();
        return result;
    }

    private void assertFailure(ProcessResult result, String expectedOutput) {
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains(expectedOutput);
    }

    private UUID printedRevision(ProcessResult result) {
        Matcher matcher = PRINTED_REVISION.matcher(result.output());
        assertThat(matcher.find()).as(result.output()).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private UUID currentPublishedRevision() throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM festival_revisions WHERE festival_id = ? AND state = 'published'"
        )) {
            statement.setObject(1, FESTIVAL_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private UUID baseRevision(UUID revisionId) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT base_revision_id FROM festival_revisions WHERE id = ?"
        )) {
            statement.setObject(1, revisionId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private String revisionState(UUID revisionId) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT state FROM festival_revisions WHERE id = ?"
        )) {
            statement.setObject(1, revisionId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private long auditCount(UUID revisionId, String action) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM catalog_revision_audit WHERE revision_id = ? AND action = ?"
        )) {
            statement.setObject(1, revisionId);
            statement.setString(2, action);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private long accountCount() throws SQLException {
        return accountScalar("SELECT count(*) FROM operational_account_settings");
    }

    private long accountHistoryCount() throws SQLException {
        return accountScalar("SELECT count(*) FROM operational_account_setting_history");
    }

    private long accountVersion() throws SQLException {
        return accountScalar("SELECT version FROM operational_account_settings WHERE festival_id = ? AND purpose = 'TICKET'", FESTIVAL_ID);
    }

    private String accountState() throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT state FROM operational_account_settings WHERE festival_id = ? AND purpose = 'TICKET'"
        )) {
            statement.setObject(1, FESTIVAL_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private List<String> accountHistoryActions() throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT operation FROM operational_account_setting_history WHERE festival_id = ? AND purpose = 'TICKET' ORDER BY id"
        )) {
            statement.setObject(1, FESTIVAL_ID);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> actions = new ArrayList<>();
                while (rows.next()) {
                    actions.add(rows.getString(1));
                }
                return actions;
            }
        }
    }

    private long accountHistoryActorCount(String actor) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM operational_account_setting_history WHERE festival_id = ? AND actor_display_name = ?"
        )) {
            statement.setObject(1, FESTIVAL_ID);
            statement.setString(2, actor);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private long accountScalar(String sql, Object... values) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(databaseUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record ProcessResult(int exitCode, String output) {}
}
