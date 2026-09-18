package dev.espero.festival;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Parses the small, explicit CLI surface and delegates to the transaction service. */
@Component
@Profile("catalog-cli")
public class CatalogCliRunner implements ApplicationRunner {

    private final CatalogRevisionService revisions;

    public CatalogCliRunner(CatalogRevisionService revisions) {
        this.revisions = revisions;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> commands = arguments.getNonOptionArgs();
        if (arguments.containsOption("help") || commands.isEmpty()) {
            usage();
            return;
        }
        String command = commands.getFirst();
        String actor = option(arguments, "actor", "catalog-cli");
        switch (command) {
            case "import" -> {
                Path manifest = Path.of(value(arguments, "manifest", commands, 1, "manifest"));
                UUID revision = revisions.importManifest(manifest, actor);
                System.out.println("draft revision: " + revision);
            }
            case "validate" -> {
                UUID revision = UUID.fromString(value(arguments, "revision", commands, 1, "revision"));
                revisions.validateRevision(revision, actor);
                System.out.println("validated revision: " + revision);
            }
            case "publish" -> {
                UUID revision = UUID.fromString(value(arguments, "revision", commands, 1, "revision"));
                revisions.publish(revision, actor);
                System.out.println("published revision: " + revision);
            }
            case "rollback" -> {
                UUID source = UUID.fromString(value(arguments, "revision", commands, 1, "revision"));
                // Stating the expected current publication makes an intervening
                // publish fail instead of being silently replaced.
                UUID expectedCurrent = expectedCurrentRevision(arguments);
                UUID revision = revisions.rollback(source, expectedCurrent, actor);
                System.out.println("rolled back to revision: " + revision);
            }
            default -> throw new CatalogCliException("Unknown command: " + command);
        }
    }

    /**
     * Parses the required {@code --expected-current} option. The literal
     * {@code none} states that the festival is expected to have no published
     * revision, which keeps "I did not check" from looking like "nothing is
     * published".
     */
    private UUID expectedCurrentRevision(ApplicationArguments arguments) {
        if (!arguments.containsOption("expected-current")) {
            throw new CatalogCliException(
                "Option --expected-current is required. Pass the currently published revision UUID, or none."
            );
        }
        String value = option(arguments, "expected-current", null);
        return "none".equals(value) ? null : UUID.fromString(value);
    }

    private String option(ApplicationArguments arguments, String name, String defaultValue) {
        if (!arguments.containsOption(name)) {
            return defaultValue;
        }
        List<String> values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new CatalogCliException("Option --" + name + " must have one non-blank value.");
        }
        return values.getFirst();
    }

    private String value(
        ApplicationArguments arguments,
        String optionName,
        List<String> commands,
        int positionalIndex,
        String argumentName
    ) {
        if (arguments.containsOption(optionName)) {
            return option(arguments, optionName, null);
        }
        return positional(commands, positionalIndex, argumentName);
    }

    private String positional(List<String> commands, int index, String name) {
        if (commands.size() <= index || commands.get(index).isBlank()) {
            throw new CatalogCliException("A " + name + " argument is required.");
        }
        return commands.get(index);
    }

    private void usage() {
        System.out.println("Usage: CatalogCliApplication <import|validate|publish|rollback> [options]");
        System.out.println("  import <manifest.json> [--actor=name]");
        System.out.println("  validate <revision-uuid> [--actor=name]");
        System.out.println("  publish <revision-uuid> [--actor=name]");
        System.out.println("  rollback <archived-revision-uuid> --expected-current=<uuid|none> [--actor=name]");
        System.out.println("  --manifest and --revision may replace the positional argument.");
        System.out.println("  A manifest states baselineRevisionId; import and publish refuse a stale baseline.");
    }
}
