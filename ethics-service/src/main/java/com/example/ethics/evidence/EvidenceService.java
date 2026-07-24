package com.example.ethics.evidence;

import com.example.ethics.api.EvidenceDtos.EvidenceDeclarationRequest;
import com.example.ethics.api.EvidenceDtos.EvidenceDeclarationResponse;
import com.example.ethics.api.EvidenceDtos.EvidenceStatusResponse;
import com.example.ethics.api.EvidenceDtos.StaffEvidenceResponse;
import com.example.ethics.config.EvidenceProperties;
import com.example.ethics.model.AuditOutbox;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.model.EvidenceAttachment;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.EvidenceAttachmentRepository;
import com.example.ethics.repository.MailboxSessionRepository;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import com.example.ethics.service.SecretHasher;
import com.example.ethics.service.TransactionKeyLock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvidenceService {
    private static final Set<String> DECLARED_TYPES = Set.of(
            "application/pdf", "text/plain", "text/plain; charset=utf-8",
            "image/jpeg", "image/png");
    private final EvidenceProperties properties;
    private final SecretHasher secrets;
    private final EthicsCaseRepository cases;
    private final MailboxSessionRepository sessions;
    private final EvidenceAttachmentRepository attachments;
    private final AuditOutboxRepository audit;
    private final EvidenceObjectStore objects;
    private final EthicsAuthorization authorization;
    private final TransactionKeyLock locks;
    private final ObjectMapper mapper;

    public EvidenceService(
            EvidenceProperties properties,
            SecretHasher secrets,
            EthicsCaseRepository cases,
            MailboxSessionRepository sessions,
            EvidenceAttachmentRepository attachments,
            AuditOutboxRepository audit,
            EvidenceObjectStore objects,
            EthicsAuthorization authorization,
            TransactionKeyLock locks,
            ObjectMapper mapper) {
        this.properties = properties;
        this.secrets = secrets;
        this.cases = cases;
        this.sessions = sessions;
        this.attachments = attachments;
        this.audit = audit;
        this.objects = objects;
        this.authorization = authorization;
        this.locks = locks;
        this.mapper = mapper;
    }

    @Transactional
    public EvidenceDeclarationResponse declare(
            String channel, String mailboxToken, String idempotencyKey,
            EvidenceDeclarationRequest request) {
        requireEnabled();
        requireIdempotencyKey(idempotencyKey);
        String normalizedChannel = normalizeChannel(channel);
        UUID caseId = caseForSession(normalizedChannel, mailboxToken);
        EthicsCase item = cases.findById(caseId).orElseThrow(EvidenceService::genericMailboxDeny);
        if ("CLOSED".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CASE_CLOSED");
        }
        String mediaType = normalizeMediaType(request.mediaType());
        if (!DECLARED_TYPES.contains(mediaType)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_MEDIA_POLICY_DENIED");
        }
        if (request.size() > properties.getMaxBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "EVIDENCE_SIZE_POLICY_FAILED");
        }
        String digest = request.sha256().toLowerCase(Locale.ROOT);
        String requestHash = secrets.sha256(
                field(mediaType) + field(Long.toString(request.size()))
                        + field(digest) + field(properties.getPolicyVersion()));
        locks.lock("evidence-declare\n" + caseId + "\n" + idempotencyKey);
        var prior = attachments.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey);
        if (prior.isPresent()) {
            EvidenceAttachment existing = prior.get();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            if ("UPLOADING".equals(existing.getState())
                    && existing.getUploadConsumedAt() == null) {
                String replacement = secrets.newSecret();
                Instant now = Instant.now();
                existing.rotateUploadCapability(
                        secrets.sha256(replacement),
                        now.plus(properties.getUploadCapabilityTtl()),
                        now);
                appendAudit(existing.getOrgId(), existing.getId(),
                        "ethics.evidence.upload_capability_rotated",
                        Map.of("reason", "idempotent_replay"));
                return declarationResponse(existing, replacement, true);
            }
            return declarationResponse(existing, null, true);
        }

        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        String capability = secrets.newSecret();
        EvidenceAttachment created = new EvidenceAttachment(
                id,
                caseId,
                item.getOrgId(),
                normalizedChannel,
                idempotencyKey,
                requestHash,
                properties.getPolicyVersion(),
                mediaType,
                request.size(),
                digest,
                "quarantine/" + UUID.randomUUID(),
                "sealed/" + UUID.randomUUID(),
                "derivative/" + UUID.randomUUID(),
                secrets.sha256(capability),
                now.plus(properties.getUploadCapabilityTtl()),
                now);
        created.startUploading(now);
        attachments.save(created);
        appendAudit(item.getOrgId(), id, "ethics.evidence.declared", Map.of(
                "policyVersion", properties.getPolicyVersion(),
                "mediaClass", mediaClass(mediaType),
                "sizeClass", sizeClass(request.size())));
        return declarationResponse(created, capability, false);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public EvidenceStatusResponse upload(
            String channel, String capability, long contentLength, InputStream body) {
        requireEnabled();
        if (capability == null || capability.isBlank()) {
            throw genericUploadDeny();
        }
        String normalizedChannel = normalizeChannel(channel);
        String capabilityHash = secrets.sha256(capability);
        EvidenceAttachment candidate = attachments
                .findByUploadCapabilityHashAndChannel(capabilityHash, normalizedChannel)
                .orElseThrow(EvidenceService::genericUploadDeny);
        // Declaration replay can rotate a capability after a response loss. Serialize both
        // paths on the same transaction key, then re-read by the presented hash so a bearer
        // invalidated while this request waited can never reach the object store.
        locks.lock("evidence-declare\n" + candidate.getCaseId()
                + "\n" + candidate.getIdempotencyKey());
        EvidenceAttachment attachment = attachments
                .findByUploadCapabilityHashAndChannel(capabilityHash, normalizedChannel)
                .orElseThrow(EvidenceService::genericUploadDeny);
        if (!"UPLOADING".equals(attachment.getState()) || attachment.getUploadConsumedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EVIDENCE_UPLOAD_ALREADY_CONSUMED");
        }
        Instant now = Instant.now();
        if (!attachment.getUploadExpiresAt().isAfter(now)) {
            attachment.expireUnbound(now);
            throw new ResponseStatusException(HttpStatus.GONE, "EVIDENCE_UPLOAD_EXPIRED");
        }
        if (contentLength != attachment.getDeclaredSize()) {
            attachment.markRejected("REJECTED_INTEGRITY", "EVIDENCE_SIZE_MISMATCH", now);
            appendAudit(attachment.getOrgId(), attachment.getId(), "ethics.evidence.rejected",
                    Map.of("outcome", "REJECTED_INTEGRITY", "reason", "EVIDENCE_SIZE_MISMATCH"));
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_SIZE_MISMATCH");
        }
        try {
            EvidenceObjectStore.ObjectReceipt quarantine =
                    objects.putQuarantine(attachment, body, contentLength);
            if (quarantine.size() != attachment.getDeclaredSize()
                    || !quarantine.sha256().equals(attachment.getDeclaredSha256())) {
                throw new EvidenceObjectStore.StoreException("EVIDENCE_OBJECT_VERIFICATION_FAILED");
            }
            attachment.markQuarantined(now);
            attachment.markIntegrityVerified(now);
            appendAudit(attachment.getOrgId(), attachment.getId(), "ethics.evidence.integrity_verified",
                    Map.of("outcome", "accepted", "sizeClass", sizeClass(contentLength)));
            try {
                EvidenceObjectStore.ObjectReceipt sealed = objects.sealOriginal(attachment);
                attachment.markOriginalSealed(
                        sealed.versionId(), sealed.sha256(), sealed.size(), Instant.now());
                appendAudit(attachment.getOrgId(), attachment.getId(), "ethics.evidence.original_sealed",
                        Map.of("outcome", "sealed", "policyVersion", attachment.getPolicyVersion()));
            } catch (EvidenceObjectStore.StoreException sealError) {
                attachment.scheduleSealRetry(
                        sealError.code(),
                        Instant.now().plus(properties.getPipeline().getRetryDelay()),
                        Instant.now());
            }
            return statusResponse(attachment);
        } catch (EvidenceObjectStore.StoreException error) {
            attachment.markRejected("REJECTED_INTEGRITY", bounded(error.code()), Instant.now());
            appendAudit(attachment.getOrgId(), attachment.getId(), "ethics.evidence.rejected",
                    Map.of("outcome", "REJECTED_INTEGRITY", "reason", bounded(error.code())));
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UPLOAD_NOT_ACCEPTED");
        }
    }

    @Transactional(readOnly = true)
    public List<EvidenceStatusResponse> reporterAttachments(String channel, String mailboxToken) {
        UUID caseId = caseForSession(normalizeChannel(channel), mailboxToken);
        return attachments.findAllByCaseIdOrderByCreatedAtAsc(caseId)
                .stream().map(EvidenceService::statusResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<StaffEvidenceResponse> staffAttachments(StaffContext staff, UUID caseId) {
        requireCase(staff, caseId, "case_viewer");
        return attachments.findAllByCaseIdOrderByCreatedAtAsc(caseId).stream()
                .map(a -> new StaffEvidenceResponse(
                        a.getId(), a.getState(),
                        a.getDerivativeMediaType() == null ? a.getDeclaredMediaType() : a.getDerivativeMediaType(),
                        a.getDerivativeSize(),
                        a.getCreatedAt(),
                        "AVAILABLE".equals(a.getState())))
                .toList();
    }

    @Transactional(readOnly = true)
    public EvidenceDownload downloadDerivative(
            StaffContext staff, UUID caseId, UUID attachmentId) {
        requireCase(staff, caseId, "case_viewer");
        EvidenceAttachment attachment = attachments.findByIdAndCaseId(attachmentId, caseId)
                .orElseThrow(EvidenceService::notFound);
        if (!"AVAILABLE".equals(attachment.getState())) throw notFound();
        byte[] body = objects.readDerivative(attachment);
        if (body.length != attachment.getDerivativeSize()
                || !secrets.sha256(body).equals(attachment.getDerivativeSha256())) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_DERIVATIVE_INTEGRITY_FAILED");
        }
        return new EvidenceDownload(body, attachment.getDerivativeMediaType());
    }

    private UUID caseForSession(String channel, String token) {
        if (token == null || token.isBlank()) throw genericMailboxDeny();
        var session = sessions.findById(secrets.sha256(token))
                .orElseThrow(EvidenceService::genericMailboxDeny);
        if (session.getExpiresAt().isBefore(Instant.now())
                || !session.getChannel().equals(channel)) {
            throw genericMailboxDeny();
        }
        return session.getCaseId();
    }

    private EthicsCase requireCase(StaffContext staff, UUID caseId, String relation) {
        EthicsCase item = cases.findByIdAndOrgId(caseId, staff.orgId())
                .orElseThrow(EvidenceService::notFound);
        authorization.require(staff, relation, caseId);
        return item;
    }

    private void appendAudit(UUID orgId, UUID attachmentId, String event, Map<String, Object> payload) {
        try {
            audit.save(new AuditOutbox(
                    UUID.randomUUID(), orgId, attachmentId, event,
                    mapper.writeValueAsString(payload), Instant.now()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Evidence audit payload could not be serialized", error);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_ATTACHMENTS_UNAVAILABLE");
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED");
        }
    }

    private static EvidenceDeclarationResponse declarationResponse(
            EvidenceAttachment attachment, String capability, boolean replay) {
        return new EvidenceDeclarationResponse(
                attachment.getId(),
                attachment.getState(),
                "/api/v1/public/ethics/evidence/uploads",
                capability,
                attachment.getUploadExpiresAt(),
                replay);
    }

    private static EvidenceStatusResponse statusResponse(EvidenceAttachment attachment) {
        return new EvidenceStatusResponse(
                attachment.getId(),
                attachment.getState(),
                attachment.getDeclaredMediaType(),
                attachment.getDeclaredSize(),
                attachment.getFailureCode(),
                attachment.getCreatedAt(),
                attachment.getUpdatedAt());
    }

    private static String normalizeMediaType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        if ("text/plain;charset=utf-8".equals(normalized)) return "text/plain; charset=utf-8";
        return normalized;
    }

    private static String normalizeChannel(String channel) {
        String value = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
        if (!Set.of("etik.acik.com", "speakup.acik.com").contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PUBLIC_CHANNEL_INVALID");
        }
        return value;
    }

    private static String field(String value) { return value.length() + ":" + value; }
    private static String mediaClass(String mediaType) {
        if (mediaType.startsWith("image/")) return "image";
        if (mediaType.startsWith("text/")) return "text";
        return "document";
    }
    private static String sizeClass(long size) {
        if (size <= 1_048_576L) return "small";
        if (size <= 10_485_760L) return "medium";
        return "large";
    }
    private static String bounded(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,120}")) return "EVIDENCE_PROCESSING_FAILED";
        return code;
    }
    private static ResponseStatusException genericMailboxDeny() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Mailbox could not be opened.");
    }
    private static ResponseStatusException genericUploadDeny() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence upload could not be opened.");
    }
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence is unavailable.");
    }

    public record EvidenceDownload(byte[] body, String mediaType) {}
}
