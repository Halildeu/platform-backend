package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import com.example.ethics.model.AuditOutbox;
import com.example.ethics.model.EvidenceAttachment;
import com.example.ethics.model.EvidenceDerivation;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.EvidenceAttachmentRepository;
import com.example.ethics.repository.EvidenceDerivationRepository;
import com.example.ethics.service.SecretHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;

/**
 * Durable ES-104 pipeline orchestrator. It never logs object keys, attachment
 * identifiers, content, filenames, case IDs, reporter data or credentials.
 */
@Component
public class EvidencePipelineWorker {
    private static final Logger log = LoggerFactory.getLogger(EvidencePipelineWorker.class);
    private static final Set<String> BACKLOG = Set.of(
            "INTEGRITY_VERIFIED", "ORIGINAL_SEALED", "SCAN_PENDING",
            "SCANNING", "SANITIZING");
    private final EvidenceProperties properties;
    private final EvidenceAttachmentRepository attachments;
    private final EvidenceDerivationRepository derivations;
    private final AuditOutboxRepository audit;
    private final EvidenceObjectStore objects;
    private final EvidenceProcessor processor;
    private final EvidenceManifestSigner signer;
    private final SecretHasher hashes;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Counter available;
    private final Counter rejected;
    private final Counter pending;

    public EvidencePipelineWorker(
            EvidenceProperties properties,
            EvidenceAttachmentRepository attachments,
            EvidenceDerivationRepository derivations,
            AuditOutboxRepository audit,
            EvidenceObjectStore objects,
            EvidenceProcessor processor,
            EvidenceManifestSigner signer,
            SecretHasher hashes,
            ObjectMapper mapper,
            TransactionTemplate transactions,
            MeterRegistry metrics) {
        this.properties = properties;
        this.attachments = attachments;
        this.derivations = derivations;
        this.audit = audit;
        this.objects = objects;
        this.processor = processor;
        this.signer = signer;
        this.hashes = hashes;
        this.mapper = mapper;
        this.transactions = transactions;
        this.available = Counter.builder("ethics.evidence.pipeline.available")
                .description("Attachments admitted as sanitized derivatives")
                .register(metrics);
        this.rejected = Counter.builder("ethics.evidence.pipeline.rejected")
                .description("Attachments rejected by bounded outcome class")
                .register(metrics);
        this.pending = Counter.builder("ethics.evidence.pipeline.pending")
                .description("Attachment processing attempts deferred fail-closed")
                .register(metrics);
        Gauge.builder("ethics.evidence.pipeline.backlog.entries", attachments,
                        repository -> repository.countByStateIn(BACKLOG))
                .description("Attachment rows unavailable while awaiting custody processing")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${ethics.evidence.pipeline.poll-delay:5s}")
    void scheduledCycle() {
        if (properties.isEnabled() && properties.getPipeline().isEnabled()) {
            runCycle();
        }
    }

    public CycleResult runCycle() {
        if (!properties.isEnabled()) return new CycleResult(0, 0, 0, 0, 0, 0);
        Instant now = Instant.now();
        int recovered = transactions.execute(status -> attachments.recoverExpiredLeases(now));
        int expired = expireUnbound(now);
        List<UUID> due = transactions.execute(status ->
                attachments.findDueIds(
                        now, PageRequest.of(0, properties.getPipeline().getBatchSize())));
        int claimed = 0;
        int admitted = 0;
        int denied = 0;
        int deferred = 0;
        for (UUID id : due) {
            UUID claimToken = UUID.randomUUID();
            EvidenceAttachment claimedRow = claim(id, claimToken, now);
            if (claimedRow == null) continue;
            claimed++;
            try {
                byte[] original = objects.readQuarantine(claimedRow);
                if (original.length != claimedRow.getDeclaredSize()
                        || !hashes.sha256(original).equals(claimedRow.getDeclaredSha256())) {
                    throw new EvidenceProcessor.ProcessingException(
                            EvidenceProcessor.ProcessingException.Outcome.INTEGRITY,
                            "EVIDENCE_QUARANTINE_INTEGRITY_FAILED");
                }
                EvidenceProcessor.ProcessedEvidence processed =
                        processor.process(original, claimedRow.getDeclaredMediaType());
                String derivativeSha = hashes.sha256(processed.derivative());
                EvidenceObjectStore.ObjectReceipt stored = objects.putDerivative(
                        claimedRow,
                        processed.derivative(),
                        derivativeSha,
                        processed.outputMediaType());
                accept(claimedRow.getId(), claimToken, processed, stored, Instant.now());
                admitted++;
                available.increment();
            } catch (EvidenceProcessor.ProcessingException error) {
                if (handleProcessingFailure(claimedRow.getId(), claimToken, error, Instant.now())) {
                    deferred++;
                    pending.increment();
                } else {
                    denied++;
                    rejected.increment();
                }
            } catch (EvidenceObjectStore.StoreException error) {
                handleUnavailable(
                        claimedRow.getId(), claimToken, bounded(error.code()), Instant.now());
                deferred++;
                pending.increment();
            } catch (RuntimeException error) {
                handleUnavailable(
                        claimedRow.getId(), claimToken,
                        "EVIDENCE_PROCESSING_UNAVAILABLE", Instant.now());
                deferred++;
                pending.increment();
            }
        }
        log.info(
                "Etik Speak evidence cycle recovered={} expired={} claimed={} available={} rejected={} pending={}",
                recovered, expired, claimed, admitted, denied, deferred);
        return new CycleResult(recovered, expired, claimed, admitted, denied, deferred);
    }

    private int expireUnbound(Instant now) {
        List<UUID> ids = transactions.execute(status -> attachments.findExpiredUnboundIds(
                now, PageRequest.of(0, properties.getPipeline().getBatchSize())));
        int expired = 0;
        for (UUID id : ids) {
            Boolean changed = transactions.execute(status -> {
                EvidenceAttachment row = attachments.findLockedById(id).orElse(null);
                if (row == null || !"UPLOADING".equals(row.getState())
                        || row.getUploadConsumedAt() != null
                        || row.getUploadExpiresAt().isAfter(now)) {
                    return false;
                }
                try {
                    objects.deleteQuarantine(row);
                } catch (EvidenceObjectStore.StoreException ignored) {
                    // A missing/partially unavailable quarantine object is not
                    // made readable. Expiry remains fail-closed and cleanup is
                    // retried by the object-store lifecycle policy.
                }
                row.expireUnbound(now);
                appendAudit(row, "ethics.evidence.expired_unbound",
                        Map.of("outcome", "expired"));
                return true;
            });
            if (Boolean.TRUE.equals(changed)) expired++;
        }
        return expired;
    }

    private EvidenceAttachment claim(UUID id, UUID token, Instant now) {
        return transactions.execute(status -> {
            EvidenceAttachment row = attachments.findLockedById(id).orElse(null);
            if (row == null) return null;
            if ("INTEGRITY_VERIFIED".equals(row.getState())) {
                try {
                    EvidenceObjectStore.ObjectReceipt sealed = objects.sealOriginal(row);
                    row.markOriginalSealed(
                            sealed.versionId(), sealed.sha256(), sealed.size(), now);
                    appendAudit(row, "ethics.evidence.original_sealed",
                            Map.of("outcome", "sealed", "policyVersion", row.getPolicyVersion()));
                } catch (EvidenceObjectStore.StoreException error) {
                    row.scheduleSealRetry(
                            bounded(error.code()),
                            now.plus(properties.getPipeline().getRetryDelay()),
                            now);
                    return null;
                }
            }
            if (!("ORIGINAL_SEALED".equals(row.getState())
                    || "SCAN_PENDING".equals(row.getState()))) {
                return null;
            }
            row.markScanning(
                    token, now.plus(properties.getPipeline().getLeaseDuration()), now);
            return row;
        });
    }

    private void accept(
            UUID id,
            UUID claimToken,
            EvidenceProcessor.ProcessedEvidence processed,
            EvidenceObjectStore.ObjectReceipt derivative,
            Instant now) {
        transactions.executeWithoutResult(status -> {
            EvidenceAttachment row = requireClaim(id, claimToken);
            row.markSanitizing(now);
            row.markDerivativeReady(
                    derivative.versionId(),
                    derivative.sha256(),
                    derivative.size(),
                    processed.outputMediaType(),
                    now);
            EvidenceDerivation previous = derivations
                    .findFirstByAttachmentIdOrderByDerivationVersionDesc(id)
                    .orElse(null);
            int version = previous == null ? 1 : previous.getDerivationVersion() + 1;
            String previousHash = previous == null ? null : previous.getManifestHash();
            EvidenceManifestSigner.SignedManifest manifest = signer.sign(
                    row, processed, derivative.sha256(), derivative.size(),
                    previousHash, version, now);
            derivations.save(new EvidenceDerivation(
                    UUID.randomUUID(),
                    row.getId(),
                    version,
                    row.getSealedSha256(),
                    row.getSealedSize(),
                    derivative.sha256(),
                    derivative.size(),
                    processed.inputMediaType(),
                    processed.outputMediaType(),
                    processed.scannerDigest(),
                    processed.sanitizerDigest(),
                    processed.parserDigest(),
                    processed.rulesVersion(),
                    row.getPolicyVersion(),
                    processed.transformationProfile(),
                    previousHash,
                    manifest.hash(),
                    manifest.signature(),
                    now));
            row.markAvailable(now);
            appendAudit(row, "ethics.evidence.available", Map.of(
                    "outcome", "available",
                    "policyVersion", row.getPolicyVersion(),
                    "derivationVersion", version));
        });
    }

    private boolean handleProcessingFailure(
            UUID id, UUID claimToken,
            EvidenceProcessor.ProcessingException error, Instant now) {
        return switch (error.outcome()) {
            case UNAVAILABLE -> {
                handleUnavailable(id, claimToken, bounded(error.code()), now);
                yield true;
            }
            case INTEGRITY -> {
                reject(id, claimToken, "REJECTED_INTEGRITY", bounded(error.code()), now);
                yield false;
            }
            case POLICY -> {
                reject(id, claimToken, "REJECTED_POLICY", bounded(error.code()), now);
                yield false;
            }
            case MALICIOUS -> {
                reject(id, claimToken, "MALICIOUS_QUARANTINED", bounded(error.code()), now);
                yield false;
            }
            case SANITIZE_FAILED -> {
                reject(id, claimToken, "SANITIZE_FAILED", bounded(error.code()), now);
                yield false;
            }
        };
    }

    private void handleUnavailable(
            UUID id, UUID claimToken, String code, Instant now) {
        transactions.executeWithoutResult(status -> {
            EvidenceAttachment row = requireClaim(id, claimToken);
            if (row.getAttemptCount() >= properties.getPipeline().getMaxAttempts()) {
                row.markRejected("SANITIZE_FAILED", "EVIDENCE_RETRY_EXHAUSTED", now);
                appendAudit(row, "ethics.evidence.rejected",
                        Map.of("outcome", "SANITIZE_FAILED", "reason", "EVIDENCE_RETRY_EXHAUSTED"));
            } else {
                row.markScanPending(
                        code, now.plus(properties.getPipeline().getRetryDelay()), now);
                appendAudit(row, "ethics.evidence.scan_pending",
                        Map.of("outcome", "pending", "reason", code));
            }
        });
    }

    private void reject(
            UUID id, UUID claimToken, String targetState, String code, Instant now) {
        transactions.executeWithoutResult(status -> {
            EvidenceAttachment row = requireClaim(id, claimToken);
            row.markRejected(targetState, code, now);
            appendAudit(row, "ethics.evidence.rejected",
                    Map.of("outcome", targetState, "reason", code));
        });
    }

    private EvidenceAttachment requireClaim(UUID id, UUID token) {
        EvidenceAttachment row = attachments.findLockedById(id).orElseThrow();
        if (!token.equals(row.getClaimToken())
                || !Set.of("SCANNING", "SANITIZING").contains(row.getState())) {
            throw new IllegalStateException("Evidence claim no longer owns the row");
        }
        return row;
    }

    private void appendAudit(
            EvidenceAttachment row, String event, Map<String, Object> payload) {
        try {
            audit.save(new AuditOutbox(
                    UUID.randomUUID(), row.getOrgId(), row.getId(), event,
                    mapper.writeValueAsString(payload), Instant.now()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Evidence audit payload could not be serialized", error);
        }
    }

    private static String bounded(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,120}")) {
            return "EVIDENCE_PROCESSING_FAILED";
        }
        return code;
    }

    public record CycleResult(
            int recovered,
            int expired,
            int claimed,
            int available,
            int rejected,
            int pending) {}
}
