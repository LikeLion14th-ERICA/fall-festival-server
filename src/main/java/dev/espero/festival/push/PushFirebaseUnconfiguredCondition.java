package dev.espero.festival.push;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code festival.push.firebase-credentials-path} is not set,
 * the exact opposite of the {@code @ConditionalOnProperty} that enables the
 * real Firebase-backed service.
 */
public class PushFirebaseUnconfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !context.getEnvironment().containsProperty("festival.push.firebase-credentials-path");
    }
}
