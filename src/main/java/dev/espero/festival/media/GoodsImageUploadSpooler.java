package dev.espero.festival.media;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.stereotype.Component;

/** Streams an untrusted multipart body into a server-owned bounded temporary file. */
@Component
public class GoodsImageUploadSpooler {

    public static final long MAX_SOURCE_BYTES = 10L * 1024 * 1024;
    private static final int COPY_BUFFER_BYTES = 16 * 1024;

    private final Path temporaryRoot;

    public GoodsImageUploadSpooler() {
        this(null);
    }

    GoodsImageUploadSpooler(Path temporaryRoot) {
        this.temporaryRoot = temporaryRoot;
    }

    public SpoolFile spool(InputStream source) throws IOException {
        Path spool = createSpoolFile();
        try (
            InputStream input = new BufferedInputStream(source);
            OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                spool,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ))
        ) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long copied = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > MAX_SOURCE_BYTES - copied) {
                    throw new GoodsImageUploadTooLargeException();
                }
                output.write(buffer, 0, read);
                copied += read;
            }
            return new SpoolFile(spool, copied);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(spool);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private Path createSpoolFile() throws IOException {
        if (temporaryRoot == null) {
            return Files.createTempFile("festival-goods-upload-", ".bin");
        }
        Files.createDirectories(temporaryRoot);
        return Files.createTempFile(temporaryRoot, "festival-goods-upload-", ".bin");
    }

    public record SpoolFile(Path path, long sizeBytes) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
