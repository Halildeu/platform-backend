package com.serban.notify.repository;

import com.serban.notify.domain.EmailSuppression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link EmailSuppression} (Faz 23.8 M7 T4.3.b).
 *
 * <p>Composite primary key {@code (org_id, channel, recipient_hash)};
 * Spring Data JPA reaches into {@link EmailSuppression.Id} for lookups.
 * Most call paths use the convenience {@link
 * #findByOrgIdAndRecipientHash} which assumes the canonical
 * {@code channel = "email"} discriminator.
 */
@Repository
public interface EmailSuppressionRepository
    extends JpaRepository<EmailSuppression, EmailSuppression.Id> {

    /**
     * Returns the suppression row for the given (org_id, email,
     * recipient_hash) tuple, if any. Used by {@code
     * DeliveryEligibilityService} on every email dispatch.
     */
    Optional<EmailSuppression> findByOrgIdAndChannelAndRecipientHash(
        String orgId,
        String channel,
        String recipientHash
    );

    /**
     * Convenience for the canonical email channel.
     */
    default Optional<EmailSuppression> findByOrgIdAndRecipientHash(
        String orgId,
        String recipientHash
    ) {
        return findByOrgIdAndChannelAndRecipientHash(orgId, "email", recipientHash);
    }

    /**
     * Admin search: list all email suppressions in an org, ordered by
     * most recent first. Pagination handled at controller layer (PR
     * scope minimal — limit fixed at 100).
     */
    List<EmailSuppression> findByOrgIdAndChannelOrderByUpdatedAtDesc(
        String orgId,
        String channel
    );
}
