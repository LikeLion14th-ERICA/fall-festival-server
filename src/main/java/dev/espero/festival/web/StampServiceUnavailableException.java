package dev.espero.festival.web;

/** Internal cause is logged safely; neither SQL nor participant credentials reach clients. */
public class StampServiceUnavailableException extends RuntimeException {

    public StampServiceUnavailableException(Throwable cause) {
        super("일시적으로 스탬프를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", cause);
    }
}
