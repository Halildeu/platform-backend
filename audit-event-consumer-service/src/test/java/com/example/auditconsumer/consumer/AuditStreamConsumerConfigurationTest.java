package com.example.auditconsumer.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.auditconsumer.config.AuditConsumerProperties;
import com.example.auditconsumer.service.AuditEventPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class AuditStreamConsumerConfigurationTest {

    @Test
    void rejectsUnboundedOrNonExpiringDlqAtStartup() {
        AuditConsumerProperties zeroMaxLen = new AuditConsumerProperties();
        zeroMaxLen.setDlqMaxLen(0);
        assertInvalid(zeroMaxLen, "dlq-max-len");

        AuditConsumerProperties zeroTtl = new AuditConsumerProperties();
        zeroTtl.setDlqTtlSeconds(0);
        assertInvalid(zeroTtl, "dlq-ttl-seconds");

        AuditConsumerProperties blankKey = new AuditConsumerProperties();
        blankKey.setDlqStreamKey(" ");
        assertInvalid(blankKey, "dlq-stream-key");
    }

    @Test
    void rejectsUnboundedConsentDependencyRetriesAtStartup() {
        AuditConsumerProperties properties = new AuditConsumerProperties();
        properties.getPoll().setDependencyMaxDeliveryAttempts(0);

        assertInvalid(properties, "dependency-max-delivery-attempts");
    }

    @Test
    void rejectsNegativeHealthThresholdsAtStartup() {
        AuditConsumerProperties negativePending = new AuditConsumerProperties();
        negativePending.getHealth().setMaxPendingForHealthy(-1);
        assertInvalid(negativePending, "max-pending-for-healthy");

        AuditConsumerProperties negativeStreamLength = new AuditConsumerProperties();
        negativeStreamLength.getHealth().setMaxStreamLengthForHealthy(-1);
        assertInvalid(negativeStreamLength, "max-stream-length-for-healthy");
    }

    private static void assertInvalid(AuditConsumerProperties properties, String expectedSetting) {
        assertThatThrownBy(() -> new AuditStreamConsumer(
                mock(StringRedisTemplate.class),
                mock(AuditEventPersistenceService.class),
                properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedSetting);
    }
}
