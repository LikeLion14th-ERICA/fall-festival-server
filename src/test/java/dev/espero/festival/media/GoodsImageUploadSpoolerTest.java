package dev.espero.festival.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoodsImageUploadSpoolerTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void acceptsExactlyTenMebibytesAndDeletesTheServerOwnedSpoolOnClose() throws Exception {
        GoodsImageUploadSpooler spooler = new GoodsImageUploadSpooler(temporaryRoot);

        Path spoolPath;
        try (GoodsImageUploadSpooler.SpoolFile spool = spooler.spool(generatedBytes(10L * 1024 * 1024))) {
            spoolPath = spool.path();
            assertThat(Files.size(spoolPath)).isEqualTo(10L * 1024 * 1024);
            assertThat(spoolPath.getParent()).isEqualTo(temporaryRoot);
            assertThat(spoolPath.getFileName().toString()).startsWith("festival-goods-upload-");
        }

        assertThat(spoolPath).doesNotExist();
    }

    @Test
    void rejectsTheFirstByteAboveTenMebibytesWithoutLeavingASpool() throws Exception {
        GoodsImageUploadSpooler spooler = new GoodsImageUploadSpooler(temporaryRoot);

        assertThatThrownBy(() -> spooler.spool(generatedBytes((10L * 1024 * 1024) + 1)))
            .isInstanceOf(GoodsImageUploadTooLargeException.class);

        try (var files = Files.list(temporaryRoot)) {
            assertThat(files).isEmpty();
        }
    }

    private static InputStream generatedBytes(long size) {
        return new InputStream() {
            private long remaining = size;

            @Override
            public int read() {
                if (remaining == 0) {
                    return -1;
                }
                remaining--;
                return 0;
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                if (length == 0) {
                    return 0;
                }
                if (remaining == 0) {
                    return -1;
                }
                int count = (int) Math.min(remaining, length);
                java.util.Arrays.fill(target, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        };
    }
}
