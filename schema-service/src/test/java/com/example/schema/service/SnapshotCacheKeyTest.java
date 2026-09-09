package com.example.schema.service;

import org.junit.jupiter.api.Test;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-backend#1149 — the snapshot cache key must resolve the primary lane
 * the same way whether the caller left {@code source} empty (the Explorer's
 * default lane) or named it (the start-up warm-up), and normalise case/space
 * the way CatalogSourceRegistry.resolve does; otherwise the same 100 s snapshot
 * is built twice.
 */
class SnapshotCacheKeyTest {

    private static String key(String source, String schema) {
        Expression expression = new SpelExpressionParser().parseExpression(SchemaSnapshotService.SNAPSHOT_CACHE_KEY);
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.setVariable("source", source);
        ctx.setVariable("schema", schema);
        return expression.getValue(ctx, String.class);
    }

    @Test
    void emptySourceAndPrimaryNameShareOneKey() {
        assertThat(key(null, "workcube_mikrolink")).isEqualTo("workcube|workcube_mikrolink");
        assertThat(key("", "workcube_mikrolink")).isEqualTo("workcube|workcube_mikrolink");
        assertThat(key("  ", "workcube_mikrolink")).isEqualTo("workcube|workcube_mikrolink");
        assertThat(key("workcube", "workcube_mikrolink")).isEqualTo("workcube|workcube_mikrolink");
        assertThat(key(" WorkCube ", "workcube_mikrolink")).isEqualTo("workcube|workcube_mikrolink");
    }

    @Test
    void otherSourcesKeepTheirOwnEntry() {
        assertThat(key("ifs", "IFSAPP")).isEqualTo("ifs|IFSAPP");
        assertThat(key("IFS", "IFSAPP")).isEqualTo("ifs|IFSAPP");
        assertThat(key("ifs", "SYS")).isNotEqualTo(key("ifs", "IFSAPP"));
    }
}
