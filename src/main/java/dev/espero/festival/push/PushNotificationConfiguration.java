package dev.espero.festival.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushNotificationProperties.class)
@ConditionalOnProperty(prefix = "festival.push", name = "firebase-credentials-path")
class PushNotificationConfiguration {

    private static final String APP_NAME = "fall-festival-push";

    @Bean
    FirebaseApp firebasePushApp(PushNotificationProperties properties) throws IOException {
        for (FirebaseApp existing : FirebaseApp.getApps()) {
            if (existing.getName().equals(APP_NAME)) {
                return existing;
            }
        }
        try (InputStream credentials = new FileInputStream(properties.configuredCredentialsPath())) {
            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(credentials))
                .build();
            return FirebaseApp.initializeApp(options, APP_NAME);
        }
    }

    @Bean
    FirebaseMessaging firebasePushMessaging(FirebaseApp firebasePushApp) {
        return FirebaseMessaging.getInstance(firebasePushApp);
    }

    @Bean
    PushNotificationService pushNotificationService(
        FirebaseMessaging firebasePushMessaging,
        PushNotificationProperties properties
    ) {
        return new FirebasePushNotificationService(
            firebasePushMessaging, properties.configuredTopic(), properties.getNoticeListUrl()
        );
    }
}
