package dev.espero.festival.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a fixed argument vector while draining and bounding diagnostic output. */
final class ExternalProcessRunner {

    static final int MAX_CAPTURE_BYTES = 64 * 1024;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(5);

    ExternalProcessResult run(List<String> command) throws IOException {
        Objects.requireNonNull(command, "Command is required");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Command must contain an executable");
        }

        Process process = new ProcessBuilder(List.copyOf(command))
            .redirectErrorStream(true)
            .start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> capture(process.getInputStream()));

        boolean completed;
        try {
            completed = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while waiting for external media tool", exception);
        }

        if (!completed) {
            process.destroyForcibly();
            awaitTermination(process);
            IOException timeout = new IOException("External media tool timed out: " + command.getFirst());
            try {
                awaitOutput(output);
            } catch (IOException captureFailure) {
                timeout.addSuppressed(captureFailure);
            }
            throw timeout;
        }

        return new ExternalProcessResult(process.exitValue(), awaitOutput(output));
    }

    private void awaitTermination(Process process) throws IOException {
        try {
            process.waitFor(TERMINATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while terminating external media tool", exception);
        }
    }

    private String awaitOutput(CompletableFuture<String> output) throws IOException {
        try {
            return output.get(TERMINATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while collecting external media tool output", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Failed to collect external media tool output", exception.getCause());
        } catch (TimeoutException exception) {
            output.cancel(true);
            throw new IOException("Timed out collecting external media tool output", exception);
        }
    }

    private String capture(InputStream input) {
        var captured = new ByteArrayOutputStream(MAX_CAPTURE_BYTES);
        byte[] buffer = new byte[8192];
        try (input) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = MAX_CAPTURE_BYTES - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(read, remaining));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to drain external media tool output", exception);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    record ExternalProcessResult(int exitCode, String output) {
    }
}
