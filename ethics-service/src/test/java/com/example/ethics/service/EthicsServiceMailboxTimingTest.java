package com.example.ethics.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.api.EthicsDtos.MailboxLoginRequest;
import com.example.ethics.config.EthicsProperties;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.EthicsMessageRepository;
import com.example.ethics.repository.EthicsReportRepository;
import com.example.ethics.repository.IntakeIdempotencyRepository;
import com.example.ethics.repository.MailboxSessionRepository;
import com.example.ethics.repository.ReporterAccessGrantRepository;
import com.example.ethics.security.EthicsAuthorization;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EthicsServiceMailboxTimingTest {
    @Mock SecretHasher secrets;
    @Mock EthicsCaseRepository cases;
    @Mock EthicsReportRepository reports;
    @Mock ReporterAccessGrantRepository grants;
    @Mock EthicsMessageRepository messages;
    @Mock MailboxSessionRepository sessions;
    @Mock AuditOutboxRepository audit;
    @Mock IntakeIdempotencyRepository idempotency;
    @Mock EthicsAuthorization authorization;
    @Mock TransactionKeyLock transactionLocks;
    EthicsService service;

    @BeforeEach
    void setUp() {
        when(secrets.newSecret()).thenReturn("process-local-dummy-secret");
        when(secrets.hash(anyString(), anyInt())).thenReturn("process-local-dummy-hash");
        service = new EthicsService(
                new EthicsProperties(UUID.randomUUID(), Duration.ofMinutes(15), 120_000,
                        "ethics-manager", "ethics-manager", true),
                secrets, cases, reports, grants, messages, sessions, audit, idempotency,
                authorization, transactionLocks);
    }

    @Test
    void missingReceiptStillPerformsPasswordHashVerification() {
        UUID missingReceipt = UUID.randomUUID();
        when(grants.findLockedByReceiptId(missingReceipt)).thenReturn(Optional.empty());
        when(secrets.verify("candidate-secret", "process-local-dummy-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.openMailbox("etik.acik.com",
                new MailboxLoginRequest(missingReceipt, "candidate-secret")))
                .isInstanceOf(ResponseStatusException.class);

        verify(secrets).verify("candidate-secret", "process-local-dummy-hash");
    }
}
