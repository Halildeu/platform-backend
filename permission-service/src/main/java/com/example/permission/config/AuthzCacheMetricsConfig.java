package com.example.permission.config;

import com.example.commonauth.scope.ScopeContextCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Exposes ScopeContextCache Caffeine stats as Micrometer metrics.
 * Metrics available at /actuator/metrics/authz.cache.*
 */
@Configuration
@ConditionalOnBean(ScopeContextCache.class)
public class AuthzCacheMetricsConfig {

    @Bean
    public MeterBinder authzCacheMetrics(ScopeContextCache cache) {
        return registry -> {
            List<Tag> tags = List.of(Tag.of("cache", "scope_context"));

            registry.gauge("authz.cache.enabled", tags, cache,
                    c -> c.isEnabled() ? 1.0 : 0.0);

            registry.gauge("authz.cache.hit.count", tags, cache,
                    c -> c.stats().hitCount());

            registry.gauge("authz.cache.miss.count", tags, cache,
                    c -> c.stats().missCount());

            registry.gauge("authz.cache.eviction.count", tags, cache,
                    c -> c.stats().evictionCount());

            registry.gauge("authz.cache.hit.rate", tags, cache,
                    c -> c.stats().hitRate());

            registry.gauge("authz.cache.load.failure.count", tags, cache,
                    c -> c.stats().loadFailureCount());

            registry.gauge("authz.cache.average.load.penalty.nanos", tags, cache,
                    c -> c.stats().averageLoadPenalty());
        };
    }
}
