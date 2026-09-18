package dev.espero.festival.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a controller method's administrator mutation concurrency rule explicit.
 * The default protects edits with an ETag; last-write-wins is an opt-in exception.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AdminMutationPolicy {
    AdminMutationConcurrency value() default AdminMutationConcurrency.IF_MATCH_REQUIRED;
}
