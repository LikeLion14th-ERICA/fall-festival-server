package dev.espero.festival.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushFcmOptions;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FirebasePushNotificationService implements PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(FirebasePushNotificationService.class);

    private final FirebaseMessaging messaging;
    private final String topic;
    private final String noticeListUrl;

    FirebasePushNotificationService(FirebaseMessaging messaging, String topic, String noticeListUrl) {
        this.messaging = messaging;
        this.topic = topic;
        this.noticeListUrl = (noticeListUrl == null || noticeListUrl.isBlank()) ? null : noticeListUrl.strip();
    }

    /**
     * Called from the public subscribe endpoint. Failures propagate so the
     * caller can tell the client registration did not take.
     */
    @Override
    public void subscribe(String deviceToken) {
        try {
            var result = messaging.subscribeToTopic(List.of(deviceToken), topic);
            if (result.getSuccessCount() != 1 || result.getFailureCount() != 0) {
                throw new PushSubscriptionFailedException(null);
            }
        } catch (FirebaseMessagingException exception) {
            log.warn("Push subscribe failed: errorCode={}", exception.getMessagingErrorCode());
            throw new PushSubscriptionFailedException(exception);
        }
    }

    /**
     * Called after a notice is already saved. A push failure here must never
     * undo or fail that write, so this only logs.
     */
    @Override
    public void notifyNoticeCreated(String title, String body) {
        Notification notification = Notification.builder().setTitle(title).setBody(body).build();
        Message.Builder message = Message.builder().setTopic(topic).setNotification(notification);
        if (noticeListUrl != null) {
            message.setWebpushConfig(
                WebpushConfig.builder().setFcmOptions(WebpushFcmOptions.withLink(noticeListUrl)).build()
            );
        }
        try {
            messaging.send(message.build());
        } catch (FirebaseMessagingException exception) {
            log.warn("Notice-created push failed: errorCode={}", exception.getMessagingErrorCode());
        }
    }
}
