package com.serban.notify.erasure;

import com.serban.notify.domain.ErasureRequestLedger;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.repository.ErasureRequestLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * KVKK Madde 13.2 erasure request ledger orchestrator (Faz 23.2 M3 R2
 * PR-K1 — Codex {@code 019e4950} P0 #1 absorb).
 *
 * <p>Açılış (open) — silme başvurusu kayda alınır:
 * <ul>
 *   <li>{@code received_at = NOW()}</li>
 *   <li>{@code due_at = received_at + 30 gün} (KVKK Madde 13.2)</li>
 *   <li>{@code status = RECEIVED}</li>
 *   <li>{@code subject_ref_hmac} PiiRedactor ile hesaplanır</li>
 *   <li>Idempotent: aynı (orgId, idempotencyKey) ikinci başvuru
 *       mevcut row döner (insert tekrarı yok)</li>
 * </ul>
 *
 * <p>Kapanış (close) — erasure işlemleri tamamlandığında:
 * <ul>
 *   <li>{@code status = COMPLETED}</li>
 *   <li>{@code closed_at = NOW()}</li>
 *   <li>{@code last_audit_event_id} bağlı audit row</li>
 * </ul>
 *
 * <p>Append-only saklanır; 90-gün retention purge buna dokunmaz.
 *
 * <p>Source derivation: caller {@code evidenceRef} string'inden
 * mantıklı tahmin yapılır (Locale.ROOT defensive — Turkish dotless-I
 * guard). Ambiguous → ADMIN default.
 */
@Service
public class ErasureRequestLedgerService {

    private static final Logger log = LoggerFactory.getLogger(ErasureRequestLedgerService.class);

    /**
     * Self-service evidence_ref sabit constant — controller path-based
     * routing. Match → SELF_SERVICE source classification.
     */
    public static final String SELF_SERVICE_EVIDENCE_REF = "self-service-kvkk-art-11";

    private final ErasureRequestLedgerRepository ledgerRepo;
    private final PiiRedactor piiRedactor;

    public ErasureRequestLedgerService(
        ErasureRequestLedgerRepository ledgerRepo,
        PiiRedactor piiRedactor
    ) {
        this.ledgerRepo = ledgerRepo;
        this.piiRedactor = piiRedactor;
    }

    /**
     * Open or fetch ledger entry — idempotent.
     *
     * <p>Eğer aynı (orgId, idempotencyKey) önceden açıldıysa mevcut
     * row döner; yeni insert yapılmaz. Bu davranış idempotent
     * erasure (ikinci API çağrısı no-op) ile uyumlu — ledger entry
     * de tek bir hukuki başvuru olarak kalır.
     *
     * <p>Açılış MUST happen-before erasure işlemleri (audit chain
     * integrity — başvuru kanıtı erasure'dan önce yazılır).
     *
     * @param orgId          tenant boundary
     * @param subscriberId   subscriber to erase (raw; HMAC içeride)
     * @param evidenceRef    operator/runbook reference (PiiRedactor
     *                       whitelist'te audit'e girer)
     * @param idempotencyKey caller-provided veya null (auto-derive)
     * @return ledger row (yeni veya mevcut)
     */
    @Transactional
    public ErasureRequestLedger openRequest(
        String orgId,
        String subscriberId,
        String evidenceRef,
        String idempotencyKey
    ) {
        if (orgId == null || subscriberId == null) {
            throw new IllegalArgumentException("openRequest: orgId/subscriberId required");
        }

        String resolvedKey = (idempotencyKey != null && !idempotencyKey.isBlank())
            ? idempotencyKey
            : deriveIdempotencyKey(orgId, subscriberId, evidenceRef);

        // Idempotent check — aynı (orgId, key) ikinci başvuru mevcut row
        var existing = ledgerRepo.findByOrgIdAndIdempotencyKey(orgId, resolvedKey);
        if (existing.isPresent()) {
            log.info("KVKK ledger: idempotent open hit orgId={} requestId={} status={}",
                orgId, existing.get().getRequestId(), existing.get().getStatus());
            return existing.get();
        }

        // PiiRedactor.hashRecipient — subscriber type (pseudonymous)
        String subjectRefHmac = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);
        ErasureRequestLedger.RequestSource source = classifySource(evidenceRef);

        ErasureRequestLedger entry = new ErasureRequestLedger();
        entry.setOrgId(orgId);
        entry.setSubjectRefHmac(subjectRefHmac);
        entry.setRequestSource(source);
        entry.setStatus(ErasureRequestLedger.Status.RECEIVED);
        entry.setIdempotencyKey(resolvedKey);
        // received_at + due_at + created_at + updated_at @PrePersist'te

        try {
            ErasureRequestLedger saved = ledgerRepo.save(entry);
            log.info("KVKK ledger: opened orgId={} requestId={} source={} dueAt={}",
                orgId, saved.getRequestId(), source, saved.getDueAt());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert race — aynı idempotency key ikinci
            // istek aynı anda geldi. Mevcut row'u tekrar fetch et.
            log.warn("KVKK ledger: concurrent insert race orgId={} key=<redacted>",
                orgId);
            return ledgerRepo.findByOrgIdAndIdempotencyKey(orgId, resolvedKey)
                .orElseThrow(() -> new IllegalStateException(
                    "Ledger unique violation but row missing — schema corruption?", e
                ));
        }
    }

    /**
     * Status PROCESSING → mark before erasure işlemleri başlar.
     * RECEIVED → PROCESSING transition.
     *
     * <p>Idempotent: zaten PROCESSING / COMPLETED / FAILED ise no-op.
     */
    @Transactional
    public void markProcessing(UUID requestId) {
        ledgerRepo.findById(requestId).ifPresent(entry -> {
            if (entry.getStatus() == ErasureRequestLedger.Status.RECEIVED) {
                entry.setStatus(ErasureRequestLedger.Status.PROCESSING);
                ledgerRepo.save(entry);
                log.debug("KVKK ledger: marked PROCESSING requestId={}", requestId);
            }
        });
    }

    /**
     * Close request — erasure işlemleri başarıyla tamamlandı.
     *
     * <p>Status → COMPLETED, closed_at = NOW(), last_audit_event_id
     * bağlı audit row. Idempotent: zaten COMPLETED ise no-op.
     *
     * <p>Audit event ID null geçilebilir (legacy path veya audit
     * publish henüz event_id döndüremiyorsa).
     */
    @Transactional
    public void completeRequest(UUID requestId, UUID auditEventId) {
        int updated = ledgerRepo.markCompleted(
            requestId, OffsetDateTime.now(), auditEventId
        );
        if (updated > 0) {
            log.info("KVKK ledger: completed requestId={} auditEventId={}",
                requestId, auditEventId);
        } else {
            log.debug("KVKK ledger: complete no-op (terminal state) requestId={}",
                requestId);
        }
    }

    /**
     * SLA Watchdog scan — KVKK Madde 13.2 30-gün breach check.
     *
     * <p>{@code due_at <= NOW()} ve status NOT terminal (RECEIVED /
     * PROCESSING / LEGAL_HOLD) → breach list. Caller (scheduler)
     * Slack alert + DPO görünür yapar.
     */
    @Transactional(readOnly = true)
    public java.util.List<ErasureRequestLedger> findOverdueRequests() {
        return ledgerRepo.findOverdueRequests(OffsetDateTime.now());
    }

    /**
     * DPO subject lookup — KVKK Madde 13 right-to-information: bir
     * subscriber için tüm geçmiş erasure başvuruları (newest-first).
     *
     * <p>Input subscriberId raw; içeride HMAC hesaplanır → reverse
     * lookup.
     */
    @Transactional(readOnly = true)
    public java.util.List<ErasureRequestLedger> findBySubject(
        String orgId, String subscriberId
    ) {
        String hmac = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);
        return ledgerRepo.findByOrgIdAndSubjectRefHmacOrderByReceivedAtDesc(orgId, hmac);
    }

    // ========================================================================
    // Source classification — Locale.ROOT defensive
    // ========================================================================

    /**
     * Evidence_ref string'inden source classification yap. Locale.ROOT
     * defensive (Turkish dotless-I I/i edge case guard).
     *
     * <p>Match order: SELF_SERVICE sentinel → keyword scan → ADMIN
     * default.
     */
    static ErasureRequestLedger.RequestSource classifySource(String evidenceRef) {
        if (evidenceRef == null || evidenceRef.isBlank()) {
            return ErasureRequestLedger.RequestSource.ADMIN;
        }
        String lower = evidenceRef.toLowerCase(Locale.ROOT);
        if (lower.contains("self-service") || lower.contains("self_service")
            || lower.contains("kvkk-art-11") || lower.contains("kvkk art 11")) {
            return ErasureRequestLedger.RequestSource.SELF_SERVICE;
        }
        if (lower.contains("dpo")) {
            return ErasureRequestLedger.RequestSource.DPO;
        }
        if (lower.contains("compliance") || lower.contains("audit")) {
            return ErasureRequestLedger.RequestSource.COMPLIANCE_AUDIT;
        }
        if (lower.contains("legal") || lower.contains("court") || lower.contains("mahkeme")) {
            return ErasureRequestLedger.RequestSource.LEGAL;
        }
        return ErasureRequestLedger.RequestSource.ADMIN;
    }

    /**
     * Auto-derive idempotency_key when caller doesn't provide one.
     *
     * <p>Pattern: {@code <source-prefix>-<orgId>-<subscriberHashShort>-<date>}
     * for self-service (günlük dedup); admin/legal evidence_ref'i
     * dedup key olarak kullanır (zaten benzersiz operator metni).
     *
     * <p>Self-service için subscriberHashShort = HMAC'in ilk 16 char'ı
     * (privacy — raw subscriberId key'de görünmez).
     */
    String deriveIdempotencyKey(String orgId, String subscriberId, String evidenceRef) {
        var source = classifySource(evidenceRef);
        if (source == ErasureRequestLedger.RequestSource.SELF_SERVICE) {
            String hash = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);
            String today = java.time.LocalDate.now().toString(); // ISO-8601 YYYY-MM-DD
            return "self-" + orgId + "-" + hash.substring(0, 16) + "-" + today;
        }
        // Admin/legal/dpo — evidence_ref ya da fallback hash
        if (evidenceRef != null && !evidenceRef.isBlank()) {
            String prefix = source.name().toLowerCase(Locale.ROOT);
            // Truncate to fit VARCHAR(128) with prefix + separators
            String safeRef = evidenceRef.length() > 96
                ? evidenceRef.substring(0, 96)
                : evidenceRef;
            return prefix + "-" + orgId + "-" + safeRef;
        }
        // Last resort — UUID
        return "admin-" + orgId + "-" + UUID.randomUUID();
    }
}
