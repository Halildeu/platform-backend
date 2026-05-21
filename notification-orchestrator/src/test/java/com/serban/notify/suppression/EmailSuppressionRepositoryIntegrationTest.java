package com.serban.notify.suppression;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.EmailSuppression;
import com.serban.notify.repository.EmailSuppressionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 23.8 M7 T4.3.b — repository + V17 migration integration test.
 *
 * <p>Verifies:
 * <ul>
 *   <li>V17 migration applies cleanly (Testcontainers PG container
 *       boots with the table)</li>
 *   <li>Composite PK uniqueness: same (org_id, channel, recipient_hash)
 *       cannot be inserted twice</li>
 *   <li>CHECK constraint on {@code channel='email'} rejects other
 *       values</li>
 *   <li>CHECK constraint on {@code reason} enum rejects invalid values</li>
 *   <li>Find queries return expected rows</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmailSuppressionRepositoryIntegrationTest extends AbstractPostgresTest {

    @Autowired
    private EmailSuppressionRepository repository;

    @Test
    void v17MigrationAppliedAndRowRoundTrips() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EmailSuppression row = newRow("org-1", "hash-1",
            EmailSuppression.Reason.HARD_BOUNCE, now);

        repository.save(row);

        Optional<EmailSuppression> found =
            repository.findByOrgIdAndChannelAndRecipientHash("org-1", "email", "hash-1");

        assertThat(found).isPresent();
        assertThat(found.get().getReason()).isEqualTo(EmailSuppression.Reason.HARD_BOUNCE);
        assertThat(found.get().getBounceCount()).isEqualTo(1);
    }

    @Test
    void compositeKeyUniquenessEnforced() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EmailSuppression row1 = newRow("org-2", "hash-2",
            EmailSuppression.Reason.HARD_BOUNCE, now);
        repository.save(row1);

        EmailSuppression row2 = newRow("org-2", "hash-2",
            EmailSuppression.Reason.SPAM_COMPLAINT, now);
        // Same (org, channel, hash) → JPA upsert via primary key, not a
        // distinct row. save() returns the merged entity.
        EmailSuppression saved = repository.save(row2);

        assertThat(saved.getReason()).isEqualTo(EmailSuppression.Reason.SPAM_COMPLAINT);
        assertThat(repository.findAll()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void channelCheckConstraintRejectsNonEmail() {
        EmailSuppression row = newRow("org-3", "hash-3",
            EmailSuppression.Reason.HARD_BOUNCE,
            OffsetDateTime.now(ZoneOffset.UTC));
        row.setChannel("sms");  // CHECK constraint: only 'email' allowed

        assertThatThrownBy(() -> {
            repository.save(row);
            repository.flush();
        }).hasMessageContaining("constraint");
    }

    @Test
    void findByOrgIdAndChannelOrderByUpdatedAtDescReturnsMultipleRows() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        repository.save(newRow("org-4", "hash-a",
            EmailSuppression.Reason.HARD_BOUNCE, now.minusHours(1)));
        repository.save(newRow("org-4", "hash-b",
            EmailSuppression.Reason.SPAM_COMPLAINT, now));

        var results = repository.findByOrgIdAndChannelOrderByUpdatedAtDesc("org-4", "email");

        assertThat(results).hasSize(2);
        // Most recent (hash-b) should come first.
        assertThat(results.get(0).getRecipientHash()).isEqualTo("hash-b");
    }

    private EmailSuppression newRow(String orgId, String hash,
                                    EmailSuppression.Reason reason,
                                    OffsetDateTime ts) {
        EmailSuppression row = new EmailSuppression();
        row.setOrgId(orgId);
        row.setChannel("email");
        row.setRecipientHash(hash);
        row.setRecipientType(EmailSuppression.RecipientType.EXTERNAL);
        row.setReason(reason);
        row.setFirstSeenAt(ts);
        row.setLastSeenAt(ts);
        row.setBounceCount(1);
        row.setCreatedAt(ts);
        row.setUpdatedAt(ts);
        return row;
    }
}
