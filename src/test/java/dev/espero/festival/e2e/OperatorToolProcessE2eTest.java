package dev.espero.festival.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.espero.festival.AccountSettingsCliApplication;
import dev.espero.festival.CatalogCliApplication;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs non-web operator entry points in separate JVMs against only a fresh
 * Testcontainers database. This verifies process exit behavior and the actual
 * catalog/account transaction boundaries used for a release.
 */
@Testcontainers(disabledWithoutDocker = true)
class OperatorToolProcessE2eTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");
    private static final Pattern PRINTED_REVISION = Pattern.compile("(?:draft|exported|validated|published|rolled back to) revision: "
        + "([0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    private String databaseUrl;

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
            "SPRING_DATASOURCE_HIKARI_JDBC_URL", rejectedDatasource
        ), "validate", "--revision=" + rollback);
        assertFailure(unavailable, "catalog-cli: CATALOG_CLI_UNAVAILABLE");
        assertThat(unavailable.output()).doesNotContain(
            "Application run failed", "Exception in thread", rejectedDatasource, databaseUrl, POSTGRES.getPassword()
        );
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

        String rejectedDatasource = "jdbc:unsupported:account-must-not-leak";
        ProcessResult unavailable = accountWithRuntimeEnvironment(Map.of(
            "SPRING_DATASOURCE_HIKARI_JDBC_URL", rejectedDatasource
        ), "clear", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET", "--expected-version=3", "--confirm",
            "--actor=operator-tool-e2e", "--reason=release-verification", "--evidence-id=OPS-E2E-5");
        assertFailure(unavailable, "account-settings-cli: ACCOUNT_CLI_UNAVAILABLE");
        assertThat(unavailable.output()).doesNotContain(
            "Application run failed", "Exception in thread", rejectedDatasource, databaseUrl, POSTGRES.getPassword()
        );
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
