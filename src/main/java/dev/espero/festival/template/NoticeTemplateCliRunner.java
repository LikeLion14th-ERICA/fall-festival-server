package dev.espero.festival.template;

import dev.espero.festival.domain.NoticeTemplate;
import dev.espero.festival.domain.NoticeTranslation;
import dev.espero.festival.persistence.NoticeTemplateStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces the whole notice template set from a JSON file. Without
 * {@code --confirm} it only validates the file and prints what would change.
 */
@Component
@Profile("notice-template-cli")
public class NoticeTemplateCliRunner {

    static final long MAX_INPUT_BYTES = 1024 * 1024;
    static final int MAX_TEMPLATES = 100;
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Set<String> LOCALES = Set.of("ko", "en", "zh-Hans", "ja");
    private static final int MAX_NAME = 100;
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 10000;

    private final NoticeTemplateStore store;
    private final Clock clock;
    private final ObjectMapper mapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .build();

    public NoticeTemplateCliRunner(NoticeTemplateStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The input file shape. */
    record Input(List<TemplateInput> templates) {}

    record TemplateInput(String id, String name, Map<String, TranslationInput> translations) {}

    record TranslationInput(String title, String body) {}

    public void execute(String[] args, PrintStream output) {
        ApplicationArguments arguments = new DefaultApplicationArguments(args);
        List<String> commands = arguments.getNonOptionArgs();
        if (arguments.containsOption("help") || commands.isEmpty()) {
            output.println("usage: replace --input-file=<templates.json> [--confirm]");
            return;
        }
        if (commands.size() != 1 || !commands.getFirst().equals("replace")) {
            throw new NoticeTemplateCliException("TEMPLATE_CLI_COMMAND_INVALID");
        }
        List<String> files = arguments.getOptionValues("input-file");
        if (files == null || files.size() != 1 || files.getFirst().isBlank()) {
            throw new NoticeTemplateCliException("TEMPLATE_INPUT_FILE_REQUIRED");
        }
        List<NoticeTemplate> templates = parse(read(Path.of(files.getFirst())));
        boolean confirmed = arguments.containsOption("confirm");

        Set<String> current = new HashSet<>();
        store.findAll().forEach(template -> current.add(template.id()));
        Set<String> next = new HashSet<>();
        templates.forEach(template -> next.add(template.id()));
        List<String> removed = current.stream().filter(id -> !next.contains(id)).sorted().toList();
        List<String> added = templates.stream().map(NoticeTemplate::id).filter(id -> !current.contains(id)).toList();

        if (confirmed) {
            store.replaceAll(templates, clock.instant());
        }
        output.println((confirmed ? "replaced" : "preview") + ": templates=" + templates.size()
            + " added=" + added + " removed=" + removed);
        if (!confirmed) {
            output.println("Nothing was written. Run again with --confirm to replace the templates.");
        }
    }

    private String read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_INPUT_BYTES) {
                throw new NoticeTemplateCliException("TEMPLATE_INPUT_FILE_INVALID");
            }
            return Files.readString(file);
        } catch (IOException exception) {
            throw new NoticeTemplateCliException("TEMPLATE_INPUT_FILE_INVALID");
        }
    }

    List<NoticeTemplate> parse(String json) {
        Input input;
        try {
            input = mapper.readValue(json, Input.class);
        } catch (JacksonException exception) {
            throw new NoticeTemplateCliException("TEMPLATE_INPUT_INVALID");
        }
        if (input == null || input.templates() == null || input.templates().size() > MAX_TEMPLATES) {
            throw new NoticeTemplateCliException("TEMPLATE_INPUT_INVALID");
        }
        Set<String> ids = new HashSet<>();
        List<NoticeTemplate> templates = new ArrayList<>();
        for (TemplateInput template : input.templates()) {
            if (template == null || template.id() == null || !ID.matcher(template.id()).matches()) {
                throw new NoticeTemplateCliException("TEMPLATE_ID_INVALID");
            }
            if (!ids.add(template.id())) {
                throw new NoticeTemplateCliException("TEMPLATE_ID_DUPLICATE");
            }
            if (!text(template.name(), MAX_NAME)) {
                throw new NoticeTemplateCliException("TEMPLATE_NAME_INVALID");
            }
            Map<String, TranslationInput> translations = template.translations();
            if (translations == null || !translations.containsKey("ko") || !LOCALES.containsAll(translations.keySet())) {
                throw new NoticeTemplateCliException("TEMPLATE_TRANSLATIONS_INVALID");
            }
            Map<String, NoticeTranslation> converted = new LinkedHashMap<>();
            translations.forEach((locale, translation) -> {
                if (translation == null || !text(translation.title(), MAX_TITLE) || !text(translation.body(), MAX_BODY)) {
                    throw new NoticeTemplateCliException("TEMPLATE_TRANSLATIONS_INVALID");
                }
                converted.put(locale, new NoticeTranslation(translation.title(), translation.body()));
            });
            templates.add(new NoticeTemplate(template.id(), template.name(), converted));
        }
        return templates;
    }

    private static boolean text(String value, int maxLength) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= maxLength;
    }
}
