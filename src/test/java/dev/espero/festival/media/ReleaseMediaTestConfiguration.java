package dev.espero.festival.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Real image transforms/storage with the same deterministic codec seam as GoodsImageProcessorTest. */
@TestConfiguration(proxyBeanMethods = false)
public class ReleaseMediaTestConfiguration {

    @Bean
    @Primary
    GoodsImageProcessor releaseGoodsImageProcessor(MediaStorage storage, MediaStorageProperties properties) {
        return new GoodsImageProcessor(storage, new WebpTools() {
            @Override
            public WebpInspection inspect(Path source) {
                throw new AssertionError("Release HTTP fixtures use PNG input");
            }

            @Override
            public void decode(Path source, Path outputPng) {
                throw new AssertionError("PNG decoding uses ImageIO");
            }

            @Override
            public void encode(Path inputPng, Path outputWebp) throws IOException {
                var image = ImageIO.read(inputPng.toFile());
                if (image == null) {
                    throw new IOException("Expected a transformed PNG");
                }
                // Delivery verifies these deterministic bytes, not native WebP compatibility.
                Files.writeString(outputWebp, "release-webp-fixture:" + image.getWidth() + ":" + image.getRGB(0, 0));
            }
        }, new Semaphore(2), properties.configuredRoot().getParent().resolve("processing"));
    }

    @Bean
    @Primary
    GoodsImageUploadSpooler releaseGoodsImageUploadSpooler(MediaStorageProperties properties) {
        return new GoodsImageUploadSpooler(properties.configuredRoot().getParent().resolve("spool"));
    }
}
