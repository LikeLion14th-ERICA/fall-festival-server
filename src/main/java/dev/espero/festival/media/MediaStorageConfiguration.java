package dev.espero.festival.media;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaStorageProperties.class)
@ConditionalOnProperty(prefix = "festival.media", name = "storage-root")
class MediaStorageConfiguration {

    @Bean
    MediaStorage mediaStorage(MediaStorageProperties properties) throws IOException {
        return new FileSystemMediaStorage(properties.configuredRoot());
    }

    @Bean
    WebpTools webpTools() {
        return new ExternalWebpTools();
    }

    @Bean
    GoodsImageInspector goodsImageInspector(WebpTools webpTools) {
        return new GoodsImageInspector(webpTools);
    }

    @Bean
    GoodsImageProcessor goodsImageProcessor(MediaStorage mediaStorage, WebpTools webpTools) {
        return new GoodsImageProcessor(mediaStorage, webpTools);
    }
}
