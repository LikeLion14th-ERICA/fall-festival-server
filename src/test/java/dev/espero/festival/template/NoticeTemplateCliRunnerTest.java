package dev.espero.festival.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.espero.festival.domain.NoticeTemplate;
import dev.espero.festival.domain.NoticeTranslation;
import dev.espero.festival.persistence.NoticeTemplateStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoticeTemplateCliRunnerTest {

    private static final String VALID = """
        {"templates": [
          {"id": "rain-delay", "name": "우천 지연",
           "translations": {"ko": {"title": "우천으로 지연", "body": "공연이 지연됩니다."},
                            "en": {"title": "Delayed by rain", "body": "The show is delayed."}}}
        ]}
        """;

    private final NoticeTemplateStore store = mock(NoticeTemplateStore.class);
    private final NoticeTemplateCliRunner runner = new NoticeTemplateCliRunner(
        store, Clock.fixed(Instant.parse("2030-10-01T00:00:00Z"), ZoneOffset.UTC)
    );

    @TempDir
    private Path tempDir;

    @Test
    void previewsWithoutWritingAndReplacesOnlyWhenConfirmed() throws IOException {
        when(store.findAll()).thenReturn(List.of(new NoticeTemplate(
            "template-registration-required", "템플릿 등록 필요", Map.of("ko", new NoticeTranslation("a", "b"))
        )));
        Path file = write(VALID);

        String preview = run("replace", "--input-file=" + file);
        verify(store, never()).replaceAll(anyList(), any());
        assertThat(preview).contains("preview: templates=1 added=[rain-delay] removed=[template-registration-required]");

        String replaced = run("replace", "--input-file=" + file, "--confirm");
        verify(store).replaceAll(anyList(), any());
        assertThat(replaced).contains("replaced: templates=1");
    }

    @Test
    void rejectsTemplatesThatBreakTheShape() {
        assertCode(VALID.replace("rain-delay", "Rain Delay"), "TEMPLATE_ID_INVALID");
        assertCode(VALID.replace("\"ko\":", "\"fr\":"), "TEMPLATE_TRANSLATIONS_INVALID");
        assertCode(VALID.replace("공연이 지연됩니다.", " "), "TEMPLATE_TRANSLATIONS_INVALID");
        assertCode(VALID.replace("\"name\": \"우천 지연\"", "\"name\": \"\""), "TEMPLATE_NAME_INVALID");
        assertCode(VALID.replace("]}", ", " + VALID.substring(VALID.indexOf('{', 2), VALID.lastIndexOf(']')) + "]}"),
            "TEMPLATE_ID_DUPLICATE");
        assertCode("{\"templates\": [], \"extra\": 1}", "TEMPLATE_INPUT_INVALID");
        assertThat(runner.parse("{\"templates\": []}")).isEmpty();
    }

    @Test
    void requiresTheReplaceCommandAndAnInputFile() {
        assertThatThrownBy(() -> run("delete"))
            .isInstanceOf(NoticeTemplateCliException.class).hasMessage("TEMPLATE_CLI_COMMAND_INVALID");
        assertThatThrownBy(() -> run("replace"))
            .isInstanceOf(NoticeTemplateCliException.class).hasMessage("TEMPLATE_INPUT_FILE_REQUIRED");
        assertThatThrownBy(() -> run("replace", "--input-file=" + tempDir.resolve("missing.json")))
            .isInstanceOf(NoticeTemplateCliException.class).hasMessage("TEMPLATE_INPUT_FILE_INVALID");
    }

    private void assertCode(String json, String code) {
        assertThatThrownBy(() -> runner.parse(json))
            .as(code)
            .isInstanceOf(NoticeTemplateCliException.class)
            .hasMessage(code);
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("templates.json");
        Files.writeString(file, content);
        return file;
    }

    private String run(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        runner.execute(args, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
