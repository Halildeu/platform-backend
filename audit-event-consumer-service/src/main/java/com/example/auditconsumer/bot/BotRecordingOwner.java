package com.example.auditconsumer.bot;

import static com.example.common.meeting.bot.BotRecordingContract.*;
import com.example.auditconsumer.audit.AuditChainLock;
import com.example.auditconsumer.audit.AuditChainSupport;
import com.example.auditconsumer.model.AuditEvent;
import com.example.auditconsumer.repository.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Synchronous bot consent owner. The tenant lock serializes grant/retry/bind/revoke and audit appends.
 * The returned snapshot is historical admission evidence, NOT a lease authorizing ongoing PCM dispatch.
 * Legacy mobile consent events/tables are deliberately neither read nor written here.
 */
@Service
@Transactional
public class BotRecordingOwner {
    private final EntityManager em;
    private final AuditChainLock lock;
    private final AuditEventRepository audit;
    private final ObjectMapper json;
    private final Clock clock;

    public BotRecordingOwner(EntityManager em, AuditChainLock lock, AuditEventRepository audit,
                             ObjectMapper json, Clock clock) {
        this.em = em; this.lock = lock; this.audit = audit; this.json = json; this.clock = clock;
    }

    public Snapshot grant(Grant command) {
        validate(command);
        lock.lockTenantChain(command.owner().companyId());
        var existing = em.createQuery("select i from BotRecordingIntent i where i.companyId = :company "
                        + "and i.ownerIssuer = :issuer and i.ownerSubject = :subject and i.requestKey = :key", BotRecordingIntent.class)
                .setParameter("company", command.owner().companyId()).setParameter("issuer", command.owner().issuer())
                .setParameter("subject", command.owner().subject()).setParameter("key", command.requestKey()).getResultList();
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!decode(row.grantJson, Grant.class).equals(command)) throw conflict();
            // An old retry can report REVOKED/expired state; it can never renew consent or its expiry.
            return snapshot(row);
        }
        Instant now = now();
        if (!command.expiresAt().isAfter(now) || command.expiresAt().isAfter(now.plus(Duration.ofHours(24)))) throw invalid();
        var row = new BotRecordingIntent();
        row.id = UUID.randomUUID(); row.companyId = command.owner().companyId(); row.requestKey = command.requestKey();
        row.ownerIssuer = command.owner().issuer(); row.ownerSubject = command.owner().subject(); row.grantJson = encode(command);
        row.createdAt = now; row.expiresAt = command.expiresAt(); row.state = "GRANTED"; row.revision = 1;
        em.persist(row);
        append(row, "BOT_RECORDING_GRANTED");
        return snapshot(row);
    }

    public Snapshot lookup(Lookup command) {
        return snapshot(owned(command));
    }

    /** Private meeting-service read, before user ownership or worker dispatch checks. Never exposed directly to clients. */
    public Snapshot inspect(IntentRef reference) {
        if (reference == null) throw invalid();
        uuid(reference.intentId()); uuid(reference.meetingId());
        var row = em.find(BotRecordingIntent.class, reference.intentId());
        if (row == null || !decode(row.grantJson, Grant.class).meetingId().equals(reference.meetingId())) throw missing();
        return snapshot(row);
    }

    /** Recover an uncertain grant without relying on a subsequently changed directory link or consent configuration. */
    public Snapshot findRequest(RequestRef reference) {
        if (reference == null) throw invalid();
        uuid(reference.requestKey()); uuid(reference.meetingId()); text(reference.issuer(), 512); text(reference.subject(), 255);
        var rows = em.createQuery("select i from BotRecordingIntent i where i.ownerIssuer = :issuer "
                        + "and i.ownerSubject = :subject and i.requestKey = :key", BotRecordingIntent.class)
                .setParameter("issuer", reference.issuer()).setParameter("subject", reference.subject())
                .setParameter("key", reference.requestKey()).getResultList();
        if (rows.isEmpty()) throw missing();
        if (rows.size() != 1 || !decode(rows.getFirst().grantJson, Grant.class).meetingId().equals(reference.meetingId())) throw conflict();
        return snapshot(rows.getFirst());
    }

    public Snapshot bind(Bind command) {
        if (command == null || command.expectedRevision() != 1 || command.binding() == null) throw invalid();
        validate(command.binding());
        validate(command.owner());
        var row = owned(new Lookup(command.intentId(), OwnerKey.of(command.owner()), command.meetingId()));
        if (!decode(row.grantJson, Grant.class).owner().equals(command.owner())) throw conflict();
        if (row.state.equals("REVOKED") || !row.expiresAt.isAfter(now())) throw conflict();
        if (row.state.equals("BOUND")) {
            if (!decode(row.bindingJson, Binding.class).equals(command.binding())) throw conflict();
            return snapshot(row);
        }
        if (!row.state.equals("GRANTED") || row.revision != command.expectedRevision()) throw conflict();
        row.bindingJson = encode(command.binding()); row.state = "BOUND"; row.revision++;
        append(row, "BOT_RECORDING_BOUND");
        return snapshot(row);
    }

    public Snapshot revoke(Lookup command) {
        var row = owned(command);
        if (row.state.equals("REVOKED")) return snapshot(row);
        // Ownership is sufficient for withdrawal, even after expiry or loss of CAN_RECORD.
        row.state = "REVOKED"; row.revision++; row.revokedAt = now();
        append(row, "BOT_RECORDING_REVOKED");
        return snapshot(row);
    }

    private BotRecordingIntent owned(Lookup command) {
        if (command == null) throw invalid();
        uuid(command.intentId()); uuid(command.meetingId()); validate(command.owner());
        lock.lockTenantChain(command.owner().companyId());
        // Scope the query itself; cross-tenant probes cannot expose another tenant's row.
        var rows = em.createQuery("select i from BotRecordingIntent i where i.id = :id and i.companyId = :company", BotRecordingIntent.class)
                .setParameter("id", command.intentId()).setParameter("company", command.owner().companyId()).getResultList();
        if (rows.isEmpty()) throw missing();
        var row = rows.getFirst();
        var grant = decode(row.grantJson, Grant.class);
        if (!OwnerKey.of(grant.owner()).equals(command.owner()) || !grant.meetingId().equals(command.meetingId())) throw missing();
        return row;
    }

    private void append(BotRecordingIntent row, String type) {
        // The outer transaction owns state, evidence and this append. Never call REQUIRES_NEW persist().
        em.flush();
        String evidenceJson = encode(snapshot(row));
        String hash = sha256(evidenceJson);
        var event = new AuditEvent();
        event.setId(UUID.randomUUID()); event.setTenantId(row.companyId); event.setEventType(type);
        event.setSessionId(row.id.toString()); event.setUserId(decode(row.grantJson, Grant.class).owner().userId());
        event.setChunkSeq(row.revision); event.setCorrelationId(hash); event.setEventTimestamp(now());
        event.setDedupKey("bot-recording:" + row.id + ":" + row.revision);
        event.setPrevHash(audit.findTop1ByTenantIdOrderBySeqDesc(row.companyId).map(AuditEvent::getEntryHash).orElse(null));
        event.setEntryHashAlg(AuditChainSupport.HASH_ALGORITHM); event.setEntryHashVersion(AuditChainSupport.HASH_VERSION);
        event.setEntryHash(AuditChainSupport.computeEntryHash(event.getPrevHash(), event));
        // JPA uses Hibernate's configured schema; unqualified native SQL would use the wrong PG search_path.
        em.persist(event); em.flush();
        var evidence = new BotRecordingEvidence();
        evidence.id = event.getId(); evidence.intentId = row.id; evidence.revision = row.revision;
        evidence.snapshotJson = evidenceJson; evidence.snapshotHash = hash;
        em.persist(evidence); em.flush();
    }

    private Snapshot snapshot(BotRecordingIntent row) {
        return new Snapshot(1, "TEAMS_LIVE_TRANSCRIPTION", row.id, decode(row.grantJson, Grant.class), row.state, row.revision, row.createdAt,
                row.bindingJson == null ? null : decode(row.bindingJson, Binding.class), row.revokedAt);
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("bot_recording_serialization_failed"); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("bot_recording_evidence_invalid"); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void validate(Grant value) {
        if (value == null) throw invalid();
        uuid(value.requestKey()); uuid(value.meetingId()); validate(value.owner());
        text(value.consentVersion(), 100); text(value.locale(), 35);
        if (value.consentTextHash() == null || !value.consentTextHash().matches("[a-f0-9]{64}")
                || value.expiresAt() == null || !value.expiresAt().equals(value.expiresAt().truncatedTo(ChronoUnit.MICROS))) throw invalid();
    }
    private static void validate(Owner value) {
        if (value == null || value.companyId() <= 0 || value.userId() <= 0) throw invalid();
        text(value.issuer(), 512); text(value.subject(), 255); text(value.authzPrincipal(), 512);
        uuid(value.tenantId()); uuid(value.orgId()); uuid(value.microsoftTenantId()); uuid(value.organizerId());
        try {
            URI issuer = URI.create(value.issuer());
            if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null || issuer.getUserInfo() != null
                    || issuer.getFragment() != null || issuer.getQuery() != null) throw invalid();
        } catch (IllegalArgumentException e) { throw invalid(); }
    }
    private static void validate(OwnerKey value) {
        if (value == null || value.companyId() <= 0) throw invalid();
        text(value.issuer(), 512); text(value.subject(), 255);
    }
    private static void validate(Binding value) {
        if (!Objects.equals("teams-capture-worker", value.workerClientId())) throw invalid();
        identifier(value.callId()); identifier(value.mediaSessionId());
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw invalid();
    }
    private static void uuid(UUID value) { if (value == null || value.equals(new UUID(0, 0))) throw invalid(); }
    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_bot_recording_command"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "bot_recording_conflict"); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "bot_recording_not_found"); }
}
