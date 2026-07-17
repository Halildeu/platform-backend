package com.example.auditconsumer.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.auditconsumer.config.AuditConsumerProperties;
import com.example.auditconsumer.consumer.AuditStreamConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class AuditConsumerHealthIndicatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsDownWhenTransientSourceStreamCrossesThreshold() {
        ObjectProvider<AuditStreamConsumer> provider = mock(ObjectProvider.class);
        AuditStreamConsumer consumer = mock(AuditStreamConsumer.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        PendingMessagesSummary pending = mock(PendingMessagesSummary.class);
        AuditConsumerProperties properties = new AuditConsumerProperties();
        properties.getHealth().setMaxPendingForHealthy(100L);
        properties.getHealth().setMaxStreamLengthForHealthy(10L);

        when(provider.getIfAvailable()).thenReturn(consumer);
        when(consumer.isRunning()).thenReturn(true);
        when(consumer.isGroupReady()).thenReturn(true);
        when(consumer.lastLoopAtMs()).thenReturn(System.currentTimeMillis());
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.pending(properties.getStream().getKey(), properties.getGroup().getName()))
                .thenReturn(pending);
        when(pending.getTotalPendingMessages()).thenReturn(0L);
        when(streamOps.size(properties.getStream().getKey())).thenReturn(10L);

        var health = new AuditConsumerHealthIndicator(provider, redis, properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("streamLength", 10L)
                .containsEntry("streamLengthThreshold", 10L)
                .containsEntry("sourceBacklog", "above-threshold");
    }
}
