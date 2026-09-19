package dev.espero.festival.media;

import java.io.IOException;
import java.nio.file.Path;

interface WebpTools {

    WebpInspection inspect(Path source) throws IOException;

    void decode(Path source, Path outputPng) throws IOException;

    void encode(Path inputPng, Path outputWebp) throws IOException;
}
