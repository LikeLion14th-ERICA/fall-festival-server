package dev.espero.festival.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AccountSettingsCliRunnerTest {

    private static final UUID FESTIVAL_ID = UUID.fromString("ec00912b-763f-4f8f-8f57-4bdfc389ccbf");

    @TempDir
    Path temporaryDirectory;

    @Test
    void dryRunShowsOnlySafeDiffAndNeverCallsTheWriter() throws Exception {
        OperationalAccountSettingsService settings = mock(OperationalAccountSettingsService.class);
        AccountSettingsCliRunner runner = new AccountSettingsCliRunner(settings);
        Path input = writeInput("110-0000-5678", "https://example.test/new-transfer");
        OperationalAccountSetting before = setting(1, "110-0000-1234", "https://example.test/old-transfer");
        OperationalAccountSetting after = setting(2, "110-0000-5678", "https://example.test/new-transfer");
        when(settings.previewSet(eq(FESTIVAL_ID), eq(OperationalAccountPurpose.TICKET), eq(1L), any(), eq("5678")))
            .thenReturn(new OperationalAccountChangeResult(OperationalAccountChangeAction.SET, before, after, true));

        String output = run(runner, "set", "--festival-id=" + FESTIVAL_ID, "--purpose=TICKET",
            "--expected-version=1", "--input-file=" + input, "--last-four=5678");

        assertThat(output).contains(
                "mode=DRY_RUN", "changedFields=accountNumber,transferLinkUrl", "beforeState=CONFIGURED",
                "beforeAccountLastFour=1234", "accountLastFour=5678", "transferLinkConfigured=true"
            )
            .doesNotContain("테스트은행", "110-0000-1234", "110-0000-5678", "테스트예금주",
                "https://example.test/old-transfer", "https://example.test/new-transfer");
        verify(settings, never()).set(any(), any(), any(Long.class), any(), any(), any());
    }

    @Test
    void confirmRequiresAuditMetadataAndAppliesTheRequestedChange() throws Exception {
        OperationalAccountSettingsService settings = mock(OperationalAccountSettingsService.class);
        AccountSettingsCliRunner runner = new AccountSettingsCliRunner(settings);
        Path input = writeInput("110-0000-5678", null);
        OperationalAccountSetting after = setting(1, "110-0000-5678", null);
        when(settings.set(eq(FESTIVAL_ID), eq(OperationalAccountPurpose.GOODS), eq(0L), any(), eq("5678"), any()))
            .thenReturn(new OperationalAccountChangeResult(OperationalAccountChangeAction.SET, null, after, true));

        String output = run(runner, "set", "--festival-id=" + FESTIVAL_ID, "--purpose=GOODS",
            "--expected-version=0", "--input-file=" + input, "--last-four=5678", "--confirm",
            "--actor=release operator", "--reason=approved update", "--evidence-id=OPS-2026-09-18");

        ArgumentCaptor<OperationalAccountAuditMetadata> audit = ArgumentCaptor.forClass(OperationalAccountAuditMetadata.class);
        verify(settings).set(eq(FESTIVAL_ID), eq(OperationalAccountPurpose.GOODS), eq(0L), any(), eq("5678"),
            audit.capture());
        assertThat(audit.getValue()).isEqualTo(
            new OperationalAccountAuditMetadata("release operator", "approved update", "OPS-2026-09-18")
        );
        assertThat(output).contains("mode=APPLIED", "changedFields=state,bankName,accountNumber,accountHolder,transferLinkUrl")
            .doesNotContain("110-0000-5678", "테스트은행", "테스트예금주");
    }

    private Path writeInput(String accountNumber, String transferLinkUrl) throws Exception {
        Path input = temporaryDirectory.resolve("account-input.json");
        String linkField = transferLinkUrl == null ? "null" : "\"" + transferLinkUrl + "\"";
        Files.writeString(input, """
            {"bankName":"테스트은행","accountNumber":"%s","accountHolder":"테스트예금주","transferLinkUrl":%s}
            """.formatted(accountNumber, linkField), StandardCharsets.UTF_8);
        return input;
    }

    private String run(AccountSettingsCliRunner runner, String... arguments) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        runner.execute(arguments, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private OperationalAccountSetting setting(long version, String accountNumber, String transferLinkUrl) {
        return new OperationalAccountSetting(
            FESTIVAL_ID, OperationalAccountPurpose.TICKET, OperationalAccountState.CONFIGURED, version,
            "테스트은행", accountNumber, "테스트예금주", transferLinkUrl, Instant.parse("2026-09-18T00:00:00Z")
        );
    }
}
