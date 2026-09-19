package dev.espero.festival.media;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class ExternalWebpTools implements WebpTools {

    static final Path CWEBP = Path.of("/usr/bin/cwebp");
    static final Path DWEBP = Path.of("/usr/bin/dwebp");
    static final Path WEBPINFO = Path.of("/usr/bin/webpinfo");
    static final Path IMG2WEBP = Path.of("/usr/bin/img2webp");
    static final int WEBP_QUALITY = 80;

    private final ExternalProcessRunner processRunner;

    ExternalWebpTools() {
        this(new ExternalProcessRunner());
    }

    ExternalWebpTools(ExternalProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    @Override
    public WebpInspection inspect(Path source) throws IOException {
        var result = processRunner.run(List.of(WEBPINFO.toString(), "-diag", source.toString()));
        if (result.exitCode() != 0) {
            throw new GoodsImageValidationException(
                GoodsImageValidationReason.CORRUPT_IMAGE,
                "webpinfo rejected the WebP input"
            );
        }
        return WebpInfoParser.parse(result.output());
    }

    @Override
    public void decode(Path source, Path outputPng) throws IOException {
        requireSuccess(
            processRunner.run(List.of(DWEBP.toString(), source.toString(), "-o", outputPng.toString())),
            "dwebp"
        );
    }

    @Override
    public void encode(Path inputPng, Path outputWebp) throws IOException {
        requireSuccess(
            processRunner.run(List.of(
                CWEBP.toString(),
                "-metadata", "none",
                "-q", Integer.toString(WEBP_QUALITY),
                inputPng.toString(),
                "-o", outputWebp.toString()
            )),
            "cwebp"
        );
    }

    private void requireSuccess(ExternalProcessRunner.ExternalProcessResult result, String tool) throws IOException {
        if (result.exitCode() != 0) {
            throw new IOException(tool + " failed with exit code " + result.exitCode() + ": " + result.output());
        }
    }
}
