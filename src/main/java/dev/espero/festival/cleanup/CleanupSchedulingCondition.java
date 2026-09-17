package dev.espero.festival.cleanup;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Prevents a destructive scheduled bean from existing without its isolated
 * datasource and explicitly named cleanup role.
 */
final class CleanupSchedulingCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String enabled = context.getEnvironment().getProperty("festival.cleanup.schedule-enabled", "false");
        if (!Boolean.parseBoolean(enabled)) {
            return false;
        }

        String dryRun = context.getEnvironment().getProperty("festival.cleanup.dry-run", "true");
        if (Boolean.parseBoolean(dryRun)) {
            return true;
        }

        return hasText(context, "festival.cleanup.datasource.url")
            && hasText(context, "festival.cleanup.datasource.username")
            && hasText(context, "festival.cleanup.datasource.password")
            && hasText(context, "festival.cleanup.datasource.role");
    }

    private boolean hasText(ConditionContext context, String key) {
        String value = context.getEnvironment().getProperty(key);
        return value != null && !value.isBlank();
    }
}
