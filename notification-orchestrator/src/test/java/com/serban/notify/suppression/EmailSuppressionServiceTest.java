package com.serban.notify.suppression;

import com.serban.notify.domain.EmailSuppression;
import com.serban.notify.repository.EmailSuppressionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailSuppressionService} (Faz 23.8 M7 T4.3.b).
 *
 * <p>Mocks the repository and a fixed clock so transition timestamps
 * and soft-window math are deterministic.
 */
class EmailSuppressionServiceTest {

    private static final String ORG = "default";
    private static final String HASH = "sha256-abc123";
    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 21, 12, 0, 0, 0, ZoneOffset.UTC);

    private EmailSuppressionRepository repository;
    private EmailSuppressionService service;

    @BeforeEach
    void setUp() {
        repository = mock(EmailSuppressionRepository.class);
        service = new EmailSuppressionService(repository);
        service.setClock(Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void isCurrentlyActive_emptyWhenNoRow() {
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.empty());

        Optional<EmailSuppression> result = service.isCurrentlyActive(ORG, HASH);

        assertThat(result).isEmpty();
    }

    @Test
    void isCurrentlyActive_returnsRowWhenPermanentlySuppressed() {
        EmailSuppression row = new EmailSuppression();
        row.setReason(EmailSuppression.Reason.HARD_BOUNCE);
        row.setSuppressedUntil(null);  // permanent
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.of(row));

        Optional<EmailSuppression> result = service.isCurrentlyActive(ORG, HASH);

        assertThat(result).isPresent();
        assertThat(result.get().getReason()).isEqualTo(EmailSuppression.Reason.HARD_BOUNCE);
    }

    @Test
    void isCurrentlyActive_returnsRowWhenSoftHoldStillActive() {
        EmailSuppression row = new EmailSuppression();
        row.setReason(EmailSuppression.Reason.SOFT_BOUNCE_REPEATED);
        row.setSuppressedUntil(FIXED_NOW.plusDays(3));  // 3 more days hold
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.of(row));

        Optional<EmailSuppression> result = service.isCurrentlyActive(ORG, HASH);

        assertThat(result).isPresent();
    }

    @Test
    void isCurrentlyActive_emptyWhenSoftHoldExpired() {
        EmailSuppression row = new EmailSuppression();
        row.setReason(EmailSuppression.Reason.SOFT_BOUNCE_REPEATED);
        row.setSuppressedUntil(FIXED_NOW.minusDays(1));  // expired yesterday
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.of(row));

        Optional<EmailSuppression> result = service.isCurrentlyActive(ORG, HASH);

        assertThat(result).isEmpty();
    }

    @Test
    void upsert_hardBounceCreatesPermanentRow() {
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.empty());
        when(repository.save(any(EmailSuppression.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            ORG, HASH,
            EmailSuppression.RecipientType.EXTERNAL,
            EmailSuppression.Reason.HARD_BOUNCE,
            EmailSuppression.Source.DSN,
            "smtp-office365",
            "msg-id-1",
            "smtp.bounce.recipient_unknown",
            "fingerprint-1",
            "admin@example.com"
        );

        EmailSuppression result = service.upsert(input);

        assertThat(result.getReason()).isEqualTo(EmailSuppression.Reason.HARD_BOUNCE);
        assertThat(result.getSuppressedUntil()).isNull();  // permanent
        assertThat(result.getBounceCount()).isEqualTo(1);
        assertThat(result.getLastSource()).isEqualTo(EmailSuppression.Source.DSN);
        assertThat(result.getLastProvider()).isEqualTo("smtp-office365");
    }

    @Test
    void upsert_spamComplaintCreatesPermanentRow() {
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.empty());
        when(repository.save(any(EmailSuppression.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            ORG, HASH, null,
            EmailSuppression.Reason.SPAM_COMPLAINT,
            EmailSuppression.Source.PROVIDER_WEBHOOK,
            "mailgun", null, "fbl.complaint", "fp-2", "system"
        );

        EmailSuppression result = service.upsert(input);

        assertThat(result.getReason()).isEqualTo(EmailSuppression.Reason.SPAM_COMPLAINT);
        assertThat(result.getSuppressedUntil()).isNull();
    }

    @Test
    void upsert_existingRowIncrementsBounceCount() {
        EmailSuppression existing = new EmailSuppression();
        existing.setOrgId(ORG);
        existing.setChannel("email");
        existing.setRecipientHash(HASH);
        existing.setReason(EmailSuppression.Reason.HARD_BOUNCE);
        existing.setBounceCount(5);
        existing.setFirstSeenAt(FIXED_NOW.minusDays(7));
        existing.setLastSeenAt(FIXED_NOW.minusDays(7));
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.of(existing));
        when(repository.save(any(EmailSuppression.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            ORG, HASH, null,
            EmailSuppression.Reason.HARD_BOUNCE,
            EmailSuppression.Source.DSN, "smtp-office365",
            "msg-id-99", "second.bounce", "fp-99", "system"
        );

        EmailSuppression result = service.upsert(input);

        assertThat(result.getBounceCount()).isEqualTo(6);
        assertThat(result.getLastSeenAt()).isEqualTo(FIXED_NOW);
        assertThat(result.getFirstSeenAt()).isEqualTo(FIXED_NOW.minusDays(7));  // preserved
    }

    @Test
    void upsert_softBounceRepeatedSetsSoftHold() {
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.empty());
        when(repository.save(any(EmailSuppression.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            ORG, HASH, null,
            EmailSuppression.Reason.SOFT_BOUNCE_REPEATED,
            EmailSuppression.Source.DSN, "smtp", null, "soft.repeated", "fp-3", null
        );

        EmailSuppression result = service.upsert(input);

        assertThat(result.getReason()).isEqualTo(EmailSuppression.Reason.SOFT_BOUNCE_REPEATED);
        assertThat(result.getSuppressedUntil()).isEqualTo(FIXED_NOW.plusDays(7));
    }

    @Test
    void handleSoftBounce_underThresholdDoesNotEscalate() {
        // First soft bounce on a fresh recipient — should not escalate.
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.empty());
        when(repository.save(any(EmailSuppression.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            ORG, HASH, null,
            EmailSuppression.Reason.SOFT_BOUNCE_REPEATED,  // placeholder
            EmailSuppression.Source.DSN, "smtp", null, "transient.5.2.2", "fp-soft-1", null
        );

        Optional<EmailSuppression> escalated = service.handleSoftBounce(input);

        assertThat(escalated).isEmpty();
        ArgumentCaptor<EmailSuppression> captor =
            ArgumentCaptor.forClass(EmailSuppression.class);
        verify(repository).save(captor.capture());
        EmailSuppression saved = captor.getValue();
        // Tracking row written with expired suppressed_until marker.
        assertThat(saved.getBounceCount()).isEqualTo(1);
        assertThat(saved.isCurrentlyActive(FIXED_NOW)).isFalse();
    }

    @Test
    void handleSoftBounce_atThresholdEscalates() {
        // Existing row with 2 soft bounces in window → 3rd triggers escalation.
        EmailSuppression existing = new EmailSuppression();
        existing.setOrgId(ORG);
        existing.setChannel("email");
        existing.setRecipientHash(HASH);
        existing.setReason(EmailSuppression.Reason.SOFT_BOUNCE_REPEATED);
        existing.setBounceCount(2);
        existing.setSoftWindowStartedAt(FIXED_NOW.minusDays(5));
        existing.setFirstSeenAt(FIXED_NOW.minusDays(5));
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.of(existing));
        when(repository.save(any(EmailSuppression.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            ORG, HASH, null,
            EmailSuppression.Reason.SOFT_BOUNCE_REPEATED,
            EmailSuppression.Source.DSN, "smtp", null, "transient", "fp-soft-3", null
        );

        Optional<EmailSuppression> escalated = service.handleSoftBounce(input);

        assertThat(escalated).isPresent();
        assertThat(escalated.get().getBounceCount()).isEqualTo(3);
        assertThat(escalated.get().getSuppressedUntil()).isEqualTo(FIXED_NOW.plusDays(7));
    }

    @Test
    void handleSoftBounce_oldWindowResetsCount() {
        // Existing row with 2 soft bounces but window > 14 days old.
        EmailSuppression existing = new EmailSuppression();
        existing.setOrgId(ORG);
        existing.setChannel("email");
        existing.setRecipientHash(HASH);
        existing.setBounceCount(2);
        existing.setSoftWindowStartedAt(FIXED_NOW.minusDays(20));
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.of(existing));
        when(repository.save(any(EmailSuppression.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            ORG, HASH, null,
            EmailSuppression.Reason.SOFT_BOUNCE_REPEATED,
            EmailSuppression.Source.DSN, "smtp", null, "transient", "fp-soft-x", null
        );

        Optional<EmailSuppression> escalated = service.handleSoftBounce(input);

        assertThat(escalated).isEmpty();  // count reset to 1, below threshold
    }

    @Test
    void release_returnsTrueWhenDeleted() {
        EmailSuppression existing = new EmailSuppression();
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.of(existing));

        boolean result = service.release(ORG, HASH);

        assertThat(result).isTrue();
        verify(repository).delete(existing);
    }

    @Test
    void release_returnsFalseWhenNotFound() {
        when(repository.findByOrgIdAndRecipientHash(ORG, HASH)).thenReturn(Optional.empty());

        boolean result = service.release(ORG, HASH);

        assertThat(result).isFalse();
        verify(repository, never()).delete(any(EmailSuppression.class));
    }

    @Test
    void upsert_rejectsBlankOrgId() {
        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            "", HASH, null, EmailSuppression.Reason.HARD_BOUNCE,
            EmailSuppression.Source.MANUAL_API, null, null, null, null, null
        );

        assertThatThrownBy(() -> service.upsert(input))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upsert_rejectsBlankRecipientHash() {
        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            ORG, "  ", null, EmailSuppression.Reason.HARD_BOUNCE,
            EmailSuppression.Source.MANUAL_API, null, null, null, null, null
        );

        assertThatThrownBy(() -> service.upsert(input))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
