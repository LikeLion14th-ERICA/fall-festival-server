package dev.espero.festival.template;

/** A notice-template CLI failure reported by code only, never with input text. */
public class NoticeTemplateCliException extends RuntimeException {

    private final String code;

    public NoticeTemplateCliException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
