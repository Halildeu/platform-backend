package com.example.auditconsumer.health;

import com.example.auditconsumer.config.AuditConsumerProperties;
import com.example.auditconsumer.consumer.AuditStreamConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — composite consumer health.
 *
 * <p>Surfaces under {@code /actuator/health} as {@code auditConsumer}. Reports:
 * <ul>
 *   <li>loop liveness (the consumer thread is running and iterated recently);</li>
 *   <li>group readiness (consumer group created/confirmed);</li>
 *   <li>consumer lag — both aggregate group pending (XPENDING) and transient
 *       source stream length below configured thresholds.</li>
 * </ul>
 *
 * <p>DB + base Redis reachability are already covered by Boot's built-in
 * {@code db} and {@code redis} health contributors; this indicator adds the
 * consumer-specific signals. When {@code audit.consumer.enabled=false} there is
 * no consumer bean and this indicator reports UP with {@code consumer=disabled}.
 */
@Component("auditConsumer")
public class AuditConsumerHealthIndicator implements HealthIndicator {

    /** Loop is considered stalled if it has not iterated within this many ms. */
    private static final long LOOP_STALL_MS = 30_000L;

    private final ObjectProvider<AuditStreamConsumer> consumerProvider;
    private final StringRedisTemplate redis;
    private final AuditConsumerProperties props;

    public AuditConsumerHealthIndicator(ObjectProvider<AuditStreamConsumer> consumerProvider,
                                        StringRedisTemplate redis,
                                        AuditConsumerProperties props) {
        this.consumerProvider = consumerProvider;
        this.redis = redis;
        this.props = props;
    }

    @Override
    public Health health() {
        AuditStreamConsumer consumer = consumerProvider.getIfAvailable();
        if (consumer == null) {
            return Health.up().withDetail("consumer", "disabled").build();
        }

        Health.Builder builder = new Health.Builder()
                .withDetail("running", consumer.isRunning())
                .withDetail("groupReady", consumer.isGroupReady())
                .withDetail("stream", props.getStream().getKey())
                .withDetail("group", props.getGroup().getName());

        boolean down = false;

        if (!consumer.isRunning()) {
            builder.withDetail("loop", "not-running");
            down = true;
        } else {
            long sinceLoopMs = System.currentTimeMillis() - consumer.lastLoopAtMs();
            builder.withDetail("msSinceLastLoop", sinceLoopMs);
            if (consumer.lastLoopAtMs() > 0 && sinceLoopMs > LOOP_STALL_MS) {
                builder.withDetail("loop", "stalled");
                down = true;
            }
        }

        long threshold = props.getHealth().getMaxPendingForHealthy();
        if (threshold > 0) {
            try {
                PendingMessagesSummary summary = redis.opsForStream()
                        .pending(props.getStream().getKey(), props.getGroup().getName());
                long pending = summary == null ? 0L : summary.getTotalPendingMessages();
                builder.withDetail("pending", pending).withDetail("pendingThreshold", threshold);
                if (pending >= threshold) {
                    builder.withDetail("lag", "above-threshold");
                    down = true;
                }
            } catch (DataAccessException ex) {
                // Group may not exist yet (pre-first-event) or Redis is down; the
                // built-in redis indicator owns the hard reachability signal.
                builder.withDetail("pending", "unavailable: " + ex.getClass().getSimpleName());
            }
        }

        long streamThreshold = props.getHealth().getMaxStreamLengthForHealthy();
        if (streamThreshold > 0) {
            try {
                Long size = redis.opsForStream().size(props.getStream().getKey());
                long streamLength = size == null ? 0L : size;
                builder.withDetail("streamLength", streamLength)
                        .withDetail("streamLengthThreshold", streamThreshold);
                if (streamLength >= streamThreshold) {
                    builder.withDetail("sourceBacklog", "above-threshold");
                    down = true;
                }
            } catch (DataAccessException ex) {
                builder.withDetail("streamLength", "unavailable: " + ex.getClass().getSimpleName());
            }
        }

        return down ? builder.down().build() : builder.up().build();
    }
}
