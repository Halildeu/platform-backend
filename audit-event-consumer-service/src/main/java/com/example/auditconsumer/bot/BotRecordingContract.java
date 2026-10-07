package com.example.auditconsumer.bot;

import java.time.Instant;
import java.util.UUID;

/** Private meeting-service contract. Actor fields come from verified user/directory evidence, never worker input. */
public final class BotRecordingContract {
    private BotRecordingContract() {}

    public record Owner(long companyId, long userId, String issuer, String subject,
                        UUID tenantId, UUID orgId, String authzPrincipal,
                        UUID microsoftTenantId, UUID organizerId) {}
    public record Grant(UUID requestKey, UUID meetingId, Owner owner, String consentVersion,
                        String consentTextHash, String locale, Instant expiresAt) {}
    public record Binding(String workerClientId, String callId, String mediaSessionId) {}
    public record Bind(UUID intentId, Owner owner, UUID meetingId, long expectedRevision, Binding binding) {}
    /** Stable user ownership survives changes to Microsoft links and recording privileges. */
    public record OwnerKey(long companyId, String issuer, String subject) {
        public static OwnerKey of(Owner owner) { return new OwnerKey(owner.companyId(), owner.issuer(), owner.subject()); }
    }
    public record Lookup(UUID intentId, OwnerKey owner, UUID meetingId) {}
    public record Snapshot(int schemaVersion, String purpose, UUID intentId, Grant grant, String state, long revision,
                           Instant createdAt, Binding binding, Instant revokedAt) {}
}
