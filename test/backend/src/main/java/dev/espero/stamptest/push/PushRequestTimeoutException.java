package dev.espero.stamptest.push;

public class PushRequestTimeoutException extends Exception {

    public PushRequestTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
