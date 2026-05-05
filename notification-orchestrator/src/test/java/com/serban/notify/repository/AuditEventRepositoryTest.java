package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class AuditEventRepositoryTest extends AbstractPostgresTest {

    @Autowired
    AuditEventRepository repo;

    @Test
    void persistAuditEventAndQueryByCorrelation() {
        AuditEvent e1 = audit("intent-1", "INTENT_CREATED", "trace-abc-123");
        AuditEvent e2 = audit("intent-1", "DELIVERY_ATTEMPTED", "trace-abc-123");
        AuditEvent e3 = audit("intent-2", "INTENT_CREATED", "trace-xyz-999");
        repo.saveAll(List.of(e1, e2, e3));

        List<AuditEvent> trace1 = repo.findByCorrelationIdOrderByOccurredAtAsc("trace-abc-123");
        assertThat(trace1).hasSize(2);
        assertThat(trace1).extracting(AuditEvent::getEventType)
            .containsExactly("INTENT_CREATED", "DELIVERY_ATTEMPTED");
    }

    @Test
    void detailsJsonbStoredAndRetrieved() {
        AuditEvent e = audit("intent-99", "INTENT_CREATED", "trace-pii");
        e.setDetails(Map.of(
            "recipient_hash", "a3f8c000",
            "url_template", "/reset?token=<TOKEN>",
            "delivery_latency_ms", 234
        ));
        repo.save(e);

        AuditEvent found = repo.findByCorrelationIdOrderByOccurredAtAsc("trace-pii").get(0);
        assertThat(found.getDetails()).containsEntry("recipient_hash", "a3f8c000");
        assertThat(found.getDetails()).doesNotContainKey("user_email"); // PII redaction discipline
    }

    private AuditEvent audit(String intentId, String eventType, String correlationId) {
        AuditEvent e = new AuditEvent();
        e.setIntentId(intentId);
        e.setEventType(eventType);
        e.setOrgId("default");
        e.setTopicKey("test.topic");
        e.setRecipientHash("hash-redacted");
        e.setCorrelationId(correlationId);
        return e;
    }
}
