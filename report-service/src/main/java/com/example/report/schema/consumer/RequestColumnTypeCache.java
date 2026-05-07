package com.example.report.schema.consumer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Phase 2 Program 8c — Per-request column type cache.
 *
 * <p>Spec §2.1 + Codex iter-1 §5 absorb: ThreadLocal Caffeine LayerCache
 * <strong>kullanılmaz</strong> (Tier 1 zaten 5-min Caffeine cache; ikinci
 * layer overkill + ThreadLocal leak riski).
 *
 * <p>Spring {@link RequestScope} bean — request scope sona erince Spring
 * lifecycle clean-up; ThreadLocal clear endişesi yok.
 *
 * <p>Cache key: {@code reportKey + ":" + fieldName} (report-scoped).
 *
 * <p>Non-web context (scheduled jobs, unit tests): bean inject edilmez,
 * caller {@link org.springframework.beans.factory.ObjectProvider#getIfAvailable()}
 * pattern ile null check yapar.
 */
@Component
@RequestScope
public class RequestColumnTypeCache {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Cache lookup.
     *
     * @param reportKey report registry key
     * @param fieldName column field name
     * @return cached column type, or empty
     */
    public Optional<String> getColumnType(String reportKey, String fieldName) {
        if (reportKey == null || fieldName == null) {
            return Optional.empty();
        }
        String key = reportKey + ":" + fieldName;
        return Optional.ofNullable(cache.get(key));
    }

    /**
     * Cache put.
     *
     * @param reportKey  report registry key
     * @param fieldName  column field name
     * @param columnType type to cache
     */
    public void putColumnType(String reportKey, String fieldName, String columnType) {
        if (reportKey == null || fieldName == null || columnType == null) {
            return;
        }
        String key = reportKey + ":" + fieldName;
        cache.put(key, columnType);
    }

    /**
     * Cache size — diagnostic only.
     */
    public int size() {
        return cache.size();
    }
}
