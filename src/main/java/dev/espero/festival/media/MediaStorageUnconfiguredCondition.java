package dev.espero.festival.media;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code festival.media.storage-root} is not set, the exact
 * opposite of the {@code @ConditionalOnProperty} that enables media storage.
 * An empty value still counts as set and fails at startup instead.
 */
public class MediaStorageUnconfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !context.getEnvironment().containsProperty("festival.media.storage-root");
    }
}
