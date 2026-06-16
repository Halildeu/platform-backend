package com.example.auditconsumer.audit;

import com.example.auditconsumer.model.AuditEvent;
import com.example.auditconsumer.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — verifies the integrity of a
 * tenant's audit hash-chain (BE-016 {@code AuditIntegrityVerifier} reuse).
 *
 * <p>Walks every row for a tenant in {@code seq} order (oldest → newest). The
 * first row is the tenant GENESIS ({@code prev_hash == null}); each subsequent
 * row must link to the prior {@code entry_hash} and re-hash to its stored value.
 * Any tamper of a historical row (or a forked chain) is detected.
 */
@Service
public class AuditIntegrityVerifier {

    private final AuditEventRepository repository;

    public AuditIntegrityVerifier(AuditEventRepository repository) {
        this.repository = repository;
    }

    /** Verify the full chain for one tenant (numeric companyId). */
    @Transactional(readOnly = true)
    public Result verifyTenant(Long tenantId) {
        List<AuditEvent> chain = repository.findByTenantIdOrderBySeqAsc(tenantId);
        if (chain.isEmpty()) {
            return new Result(tenantId, true, 0, null, "No audit rows for tenant (empty chain).");
        }

        String expectedPrev = null; // GENESIS row must have prev_hash == null
        int checked = 0;
        for (AuditEvent event : chain) {
            if (!equalsNullable(expectedPrev, event.getPrevHash())) {
                return new Result(tenantId, false, checked, event.getId(),
                        "Chain linkage broken at event " + event.getId()
                                + ": expected prev_hash=" + describe(expectedPrev)
                                + " but stored=" + describe(event.getPrevHash()));
            }
            String recomputed = AuditChainSupport.computeEntryHash(event.getPrevHash(), event);
            if (!recomputed.equals(event.getEntryHash())) {
                return new Result(tenantId, false, checked, event.getId(),
                        "Tamper detected at event " + event.getId()
                                + ": stored entry_hash=" + describe(event.getEntryHash())
                                + " but recomputed=" + describe(recomputed));
            }
            expectedPrev = event.getEntryHash();
            checked++;
        }
        return new Result(tenantId, true, checked, null,
                "Chain verified: " + checked + " audit row(s) intact.");
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String describe(String hash) {
        return hash == null ? "<GENESIS/null>" : hash;
    }

    /** Verification outcome. {@code tenantId} is the numeric companyId; {@code firstFailureEventId} is the row UUID. */
    public record Result(
            Long tenantId,
            boolean valid,
            int checkedCount,
            UUID firstFailureEventId,
            String message
    ) {
    }
}
