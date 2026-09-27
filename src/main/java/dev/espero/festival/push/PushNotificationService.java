package dev.espero.festival.push;

/**
 * Web push for the anonymous public app. There is no per-user account, so
 * subscription is topic-based: every registered device token joins one
 * festival-wide topic and every notice fan-outs to that topic.
 */
public interface PushNotificationService {

    void subscribe(String deviceToken);

    void notifyNoticeCreated(String title, String body);
}
