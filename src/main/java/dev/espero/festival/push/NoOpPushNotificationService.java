package dev.espero.festival.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stands in when {@code festival.push.firebase-credentials-path} is not set.
 * Notices still publish normally; only the push fan-out is skipped. Without
 * this, every notice-creation request would need Firebase to be configured
 * just to save a notice.
 */
public class NoOpPushNotificationService implements PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(NoOpPushNotificationService.class);
    private volatile boolean warned;

    @Override
    public void subscribe(String deviceToken) {
        warnOnce();
    }

    @Override
    public void notifyNoticeCreated(String title, String body) {
        warnOnce();
    }

    private void warnOnce() {
        if (!warned) {
            warned = true;
            log.warn("FESTIVAL_PUSH_FIREBASE_CREDENTIALS_PATH is not set: push subscribe and "
                + "notice-created push both no-op.");
        }
    }
}
