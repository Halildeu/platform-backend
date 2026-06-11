package com.example.endpointadmin.remoteaccess;

import java.time.Instant;
import java.util.Set;

/**
 * Faz 22.6 immutable, hash-linked session audit record (ADR-0033 §6, KVKK m.12 chain-of-custody).
 * One record is emitted per state transition; {@code recordingManifestHash} + {@code chunkHashRoot}
 * link the audit to the WORM/object-locked recording bundle. This is the schema (a value object);
 * persistence/hash-chaining live behind the broker and are not part of this skeleton.
 *
 * <p>Redaction invariant (ADR-0034 D-redaction / #1388): no secret/JWT/token/raw PII/session key is
 * ever placed in this record — only ids, hashes, and an IP <i>hash</i>.
 */
public record RemoteSessionAuditRecord(
        String sessionId,
        String orgId,
        String targetDeviceId,
        String agentId,
        String agentCertThumbprint,
        String agentBinaryDigest,
        String requesterUserId,
        String approverUserId,
        boolean targetUserAck,
        Set<RemoteSessionCapability> capabilitySetRequested,
        Set<RemoteSessionCapability> capabilitySetApproved,
        String legalBasis,
        String reasonTicket,
        String tokenJti,
        String tokenKid,
        RemoteSessionState stateFrom,
        RemoteSessionState stateTo,
        Instant eventTime,
        String operatorIpHash,
        String recordingObjectUri,
        String recordingManifestHash,
        String chunkHashRoot,
        String abortReason,
        String retentionClass) {

    public RemoteSessionAuditRecord {
        capabilitySetRequested = capabilitySetRequested == null ? Set.of() : Set.copyOf(capabilitySetRequested);
        capabilitySetApproved = capabilitySetApproved == null ? Set.of() : Set.copyOf(capabilitySetApproved);
    }
}
