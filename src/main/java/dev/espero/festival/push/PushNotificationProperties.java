package dev.espero.festival.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "festival.push")
public class PushNotificationProperties {

    private String firebaseCredentialsPath;
    private String noticeTopic = "notices";
    private String noticeListUrl;

    public String getFirebaseCredentialsPath() {
        return firebaseCredentialsPath;
    }

    public void setFirebaseCredentialsPath(String firebaseCredentialsPath) {
        this.firebaseCredentialsPath = firebaseCredentialsPath;
    }

    public String getNoticeTopic() {
        return noticeTopic;
    }

    public void setNoticeTopic(String noticeTopic) {
        this.noticeTopic = noticeTopic;
    }

    public String getNoticeListUrl() {
        return noticeListUrl;
    }

    public void setNoticeListUrl(String noticeListUrl) {
        this.noticeListUrl = noticeListUrl;
    }

    String configuredCredentialsPath() {
        if (firebaseCredentialsPath == null || firebaseCredentialsPath.isBlank()) {
            throw new IllegalStateException("FESTIVAL_PUSH_FIREBASE_CREDENTIALS_PATH must be configured");
        }
        return firebaseCredentialsPath.strip();
    }

    String configuredTopic() {
        return (noticeTopic == null || noticeTopic.isBlank()) ? "notices" : noticeTopic.strip();
    }
}
