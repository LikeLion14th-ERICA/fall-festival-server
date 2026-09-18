package dev.espero.festival.account;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Parses the deliberately small, non-HTTP account-operator command surface. */
@Component
@Profile("account-settings-cli")
public class AccountSettingsCliRunner {

    private static final long MAX_INPUT_BYTES = 16 * 1024;

    private final OperationalAccountSettingsService settings;
    private final ObjectMapper mapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .build();

    public AccountSettingsCliRunner(OperationalAccountSettingsService settings) {
        this.settings = settings;
    }

    public void execute(String[] args, PrintStream output) {
        var arguments = new DefaultApplicationArguments(args);
        List<String> commands = arguments.getNonOptionArgs();
        if (arguments.containsOption("help") || commands.isEmpty()) {
            usage(output);
            return;
        }
        if (commands.size() != 1) {
            throw new OperationalAccountException("ACCOUNT_CLI_COMMAND_INVALID");
        }

        String command = commands.getFirst();
        Common common = common(arguments);
        boolean confirmed = confirm(arguments);
        OperationalAccountChangeResult result = switch (command) {
            case "set" -> set(arguments, common, confirmed);
            case "restore-version" -> restore(arguments, common, confirmed);
            case "clear" -> clear(arguments, common, confirmed);
            default -> throw new OperationalAccountException("ACCOUNT_CLI_COMMAND_INVALID");
        };
        print(output, command, common, result, confirmed);
    }

    private OperationalAccountChangeResult set(
        org.springframework.boot.ApplicationArguments arguments,
        Common common,
        boolean confirmed
    ) {
        OperationalAccountChange change = readInput(value(arguments, "input-file"));
        String lastFour = value(arguments, "last-four");
        return confirmed
            ? settings.set(
                common.festivalId(), common.purpose(), common.expectedVersion(), change, lastFour, audit(arguments)
            )
            : settings.previewSet(common.festivalId(), common.purpose(), common.expectedVersion(), change, lastFour);
    }

    private OperationalAccountChangeResult restore(
        org.springframework.boot.ApplicationArguments arguments,
        Common common,
        boolean confirmed
    ) {
        long sourceVersion = positiveLong(value(arguments, "source-version"), "ACCOUNT_HISTORY_VERSION_INVALID");
        String lastFour = optionalValue(arguments, "last-four");
        return confirmed
            ? settings.restore(
                common.festivalId(), common.purpose(), common.expectedVersion(), sourceVersion, lastFour, audit(arguments)
            )
            : settings.previewRestore(common.festivalId(), common.purpose(), common.expectedVersion(), sourceVersion, lastFour);
    }

    private OperationalAccountChangeResult clear(
        org.springframework.boot.ApplicationArguments arguments,
        Common common,
        boolean confirmed
    ) {
        return confirmed
            ? settings.clear(common.festivalId(), common.purpose(), common.expectedVersion(), audit(arguments))
            : settings.previewClear(common.festivalId(), common.purpose(), common.expectedVersion());
    }

    private Common common(org.springframework.boot.ApplicationArguments arguments) {
        UUID festivalId;
        try {
            festivalId = UUID.fromString(value(arguments, "festival-id"));
        } catch (IllegalArgumentException exception) {
            throw new OperationalAccountException("ACCOUNT_FESTIVAL_ID_INVALID");
        }
        return new Common(
            festivalId,
            OperationalAccountPurpose.parse(value(arguments, "purpose")),
            nonNegativeLong(value(arguments, "expected-version"), "ACCOUNT_EXPECTED_VERSION_MISMATCH")
        );
    }

    private OperationalAccountAuditMetadata audit(org.springframework.boot.ApplicationArguments arguments) {
        return new OperationalAccountAuditMetadata(
            value(arguments, "actor"),
            value(arguments, "reason"),
            value(arguments, "evidence-id")
        );
    }

    private boolean confirm(org.springframework.boot.ApplicationArguments arguments) {
        if (!arguments.containsOption("confirm")) {
            return false;
        }
        List<String> values = arguments.getOptionValues("confirm");
        if (values != null && !values.isEmpty()) {
            throw new OperationalAccountException("ACCOUNT_CONFIRMATION_INVALID");
        }
        return true;
    }

    private OperationalAccountChange readInput(String inputFile) {
        Path path;
        try {
            path = Path.of(inputFile);
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_INPUT_BYTES) {
                throw new OperationalAccountException("ACCOUNT_INPUT_FILE_INVALID");
            }
            try (var input = Files.newInputStream(path)) {
                return mapper.readValue(input, OperationalAccountInputDocument.class).asChange();
            }
        } catch (OperationalAccountException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new OperationalAccountException("ACCOUNT_INPUT_FILE_INVALID");
        }
    }

    private String value(org.springframework.boot.ApplicationArguments arguments, String name) {
        String value = optionalValue(arguments, name);
        if (value == null) {
            throw new OperationalAccountException("ACCOUNT_CLI_ARGUMENT_INVALID");
        }
        return value;
    }

    private String optionalValue(org.springframework.boot.ApplicationArguments arguments, String name) {
        if (!arguments.containsOption(name)) {
            return null;
        }
        List<String> values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
            throw new OperationalAccountException("ACCOUNT_CLI_ARGUMENT_INVALID");
        }
        return values.getFirst();
    }

    private long positiveLong(String value, String code) {
        long parsed = nonNegativeLong(value, code);
        if (parsed < 1) {
            throw new OperationalAccountException(code);
        }
        return parsed;
    }

    private long nonNegativeLong(String value, String code) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new OperationalAccountException(code);
        }
    }

    private void print(
        PrintStream output,
        String command,
        Common common,
        OperationalAccountChangeResult result,
        boolean confirmed
    ) {
        OperationalAccountSetting setting = result.setting();
        output.println("mode=" + (confirmed ? "APPLIED" : "DRY_RUN"));
        output.println("action=" + command);
        output.println("festivalId=" + common.festivalId());
        output.println("purpose=" + setting.purpose());
        output.println("expectedVersion=" + common.expectedVersion());
        output.println("resultVersion=" + setting.version());
        output.println("changed=" + result.changed());
        output.println("changedFields=" + changedFields(result));
        OperationalAccountSetting before = result.before();
        output.println("beforeState=" + (before == null ? "none" : before.state()));
        output.println("beforeAccountLastFour=" + lastFour(before));
        output.println("beforeTransferLinkConfigured=" + (before != null && before.transferLinkUrl() != null));
        output.println("state=" + setting.state());
        output.println("accountLastFour=" + lastFour(setting));
        output.println("transferLinkConfigured=" + (setting.transferLinkUrl() != null));
    }

    private String changedFields(OperationalAccountChangeResult result) {
        if (!result.changed()) {
            return "none";
        }
        OperationalAccountSetting before = result.before();
        if (before == null) {
            return "state,bankName,accountNumber,accountHolder,transferLinkUrl";
        }
        List<String> fields = new ArrayList<>();
        if (before.state() != result.setting().state()) {
            fields.add("state");
        }
        if (!Objects.equals(before.bankName(), result.setting().bankName())) {
            fields.add("bankName");
        }
        if (!Objects.equals(before.accountNumber(), result.setting().accountNumber())) {
            fields.add("accountNumber");
        }
        if (!Objects.equals(before.accountHolder(), result.setting().accountHolder())) {
            fields.add("accountHolder");
        }
        if (!Objects.equals(before.transferLinkUrl(), result.setting().transferLinkUrl())) {
            fields.add("transferLinkUrl");
        }
        return String.join(",", fields);
    }

    private String lastFour(OperationalAccountSetting setting) {
        return setting == null || setting.accountLastFour() == null ? "none" : setting.accountLastFour();
    }

    private void usage(PrintStream output) {
        output.println("Usage: AccountSettingsCliApplication <set|restore-version|clear> [options]");
        output.println("Required for every command: --festival-id=<uuid> --purpose=<TICKET|GOODS> --expected-version=<n>");
        output.println("set: --input-file=<local-json> --last-four=<four-digits>");
        output.println("restore-version: --source-version=<n> [--last-four=<four-digits>]");
        output.println("Apply only: --confirm --actor=<display-name> --reason=<reason> --evidence-id=<reference>");
        output.println("Without --confirm, the command is a read-only dry run.");
    }

    private record Common(UUID festivalId, OperationalAccountPurpose purpose, long expectedVersion) {}
}
