package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalProcessRunnerTest {

    @Test
    void boundsCapturedOutputWhileContinuingToDrainTheProcessPipe() throws Exception {
        var result = new ExternalProcessRunner().run(javaCommand("output", "70000"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output().getBytes(StandardCharsets.UTF_8))
            .hasSize(ExternalProcessRunner.MAX_CAPTURE_BYTES);
    }

    @Test
    void passesMetacharactersAsOneLiteralArgumentWithoutAShell() throws Exception {
        String literal = "$(not-a-command); rm -rf never";

        var result = new ExternalProcessRunner().run(javaCommand("echo", literal));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(literal);
    }

    private List<String> javaCommand(String... arguments) {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(OutputProgram.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    public static final class OutputProgram {
        private OutputProgram() {
        }

        public static void main(String[] arguments) {
            if (arguments[0].equals("output")) {
                System.out.print("x".repeat(Integer.parseInt(arguments[1])));
            } else {
                System.out.print(arguments[1]);
            }
        }
    }
}
