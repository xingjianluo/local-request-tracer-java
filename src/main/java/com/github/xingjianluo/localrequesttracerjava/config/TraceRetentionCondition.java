package com.github.xingjianluo.localrequesttracerjava.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 后台过期回收启用条件：trace.retention-ms 与 trace.sweep-interval-ms 都为正。
 * 任一为 0 / 缺省（按 0 处理）则不启动后台扫描。
 */
public class TraceRetentionCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        long retention = positiveOrZero(context.getEnvironment().getProperty("trace.retention-ms"));
        long interval = positiveOrZero(context.getEnvironment().getProperty("trace.sweep-interval-ms"));
        return retention > 0 && interval > 0;
    }

    private static long positiveOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
