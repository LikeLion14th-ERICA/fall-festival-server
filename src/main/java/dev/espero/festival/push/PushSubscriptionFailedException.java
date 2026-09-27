package dev.espero.festival.push;

public class PushSubscriptionFailedException extends RuntimeException {

    public PushSubscriptionFailedException(Throwable cause) {
        super("Failed to register the device token for push", cause);
    }
}
