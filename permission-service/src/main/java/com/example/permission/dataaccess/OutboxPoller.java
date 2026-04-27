package com.example.permission.dataaccess;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Faz 21.3 PR-G — outbox poller. Codex 019dcf5c iter-2 strategic primary;
 * iter-1 review (019dd0e0) BLOCKER 3 absorb tightens finalize semantics
 * with compare-and-set fenced UPDATEs.
 *
 * <p>Per-tick lifecycle (default 5s, configurable via
 * {@code app.outbox.poll-interval-ms}):
 * <ol>
 *   <li>{@link DataAccessScopeOutboxRepository#recoverStuckRows()} — releases
 *       PROCESSING rows whose lock expired (pod crash / hang) back to PENDING.</li>
 *   <li>{@link DataAccessScopeOutboxRepository#claimBatch} — atomically flips a
 *       batch of PENDING rows to PROCESSING (FOR UPDATE SKIP LOCKED, ordered
 *       per tuple identity per V23). Each returned row carries its
 *       {@code locked_until} which the finalize step later passes back as a
 *       CAS token.</li>
 *   <li>For each claimed entry, the OpenFGA write is performed
 *       <strong>OUTSIDE the claim TX</strong>. The outcome is persisted via
 *       fenced UPDATE: {@link DataAccessScopeOutboxRepository#markProcessed},
 *       {@link DataAccessScopeOutboxRepository#markRetry}, or
 *       {@link DataAccessScopeOutboxRepository#markFailed}. All three include
 *       {@code WHERE locked_by = :pollerId AND locked_until = :claimedLockedUntil}
 *       so a stale worker (lock already expired and another poller reclaimed)
 *       cannot overwrite the fresh worker's outcome — the CAS condition fails
 *       and we log without persisting.</li>
 * </ol>
 *
 * <p>Spring AOP self-invocation trap is avoided by using
 * {@link TransactionTemplate} explicitly rather than annotating internal
 * methods with {@code @Transactional}: the scheduler entry point
 * {@code pollAndProcess} would otherwise call the finalize helpers via
 * {@code this} and bypass the proxy.
 */
@Service
@ConditionalOnProperty(
        name = {
                "spring.datasource.reports-db.enabled",
                "erp.openfga.enabled"
        },
        havingValue = "true"
)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final DataAccessScopeOutboxRepository outboxRepository;
    private final OpenFgaAuthzService authzService;
    private final OutboxBackoffPolicy backoffPolicy;
    private final OutboxConfig config;
    private final TransactionTemplate txTemplate;
    private final String pollerId;

    public OutboxPoller(DataAccessScopeOutboxRepository outboxRepository,
                        OpenFgaAuthzService authzService,
                        OutboxBackoffPolicy backoffPolicy,
                        OutboxConfig config,
                        @Qualifier("reportsDbTransactionManager") PlatformTransactionManager txManager,
                        Environment env) {
        this.outboxRepository = outboxRepository;
        this.authzService = authzService;
        this.backoffPolicy = backoffPolicy;
        this.config = config;
        this.txTemplate = new TransactionTemplate(txManager);
        this.pollerId = resolvePollerId(env);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    public void pollAndProcess() {
        try {
            int recovered = txTemplate.execute(s -> outboxRepository.recoverStuckRows());
            if (recovered > 0) {
                log.info("outbox poller recovered {} stuck PROCESSING rows", recovered);
            }
        } catch (Exception e) {
            log.warn("outbox recoverStuckRows failed (non-fatal): {}", e.getMessage());
        }

        Instant lockUntil = Instant.now().plus(config.getProcessingLockTtl());
        List<DataAccessScopeOutboxEntry> claimed;
        try {
            claimed = txTemplate.execute(s ->
                    outboxRepository.claimBatch(pollerId, lockUntil, config.getBatchSize()));
        } catch (Exception e) {
            log.warn("outbox claimBatch failed (non-fatal): {}", e.getMessage());
            return;
        }
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        log.debug("outbox poller claimed {} entries", claimed.size());

        for (DataAccessScopeOutboxEntry entry : claimed) {
            // Each row's locked_until is the CAS token for its finalize.
            // We snapshot it here (rather than re-reading later) so a slow
            // FGA call cannot make us look at a stale value if the row got
            // reclaimed by the recovery sweep mid-call.
            Instant claimedLockedUntil = entry.getLockedUntil();
            processEntry(entry, claimedLockedUntil);
        }
    }

    /**
     * Visible for tests — exercised directly so the test does not have to
     * stand up a full {@link Scheduled} thread.
     */
    void processEntry(DataAccessScopeOutboxEntry entry, Instant claimedLockedUntil) {
        try {
            invokeFga(entry);
            finalizeProcessed(entry, claimedLockedUntil);
        } catch (Exception cause) {
            int attempts = entry.getAttemptCount() == null ? 0 : entry.getAttemptCount();
            if (attempts >= config.getMaxAttempts()) {
                markTerminalFailure(entry, claimedLockedUntil, cause);
            } else {
                scheduleRetry(entry, claimedLockedUntil, cause);
            }
        }
    }

    private void invokeFga(DataAccessScopeOutboxEntry entry) {
        Map<String, Object> tuple = extractTuple(entry.getPayload());
        String tupleUserPrefixed = (String) tuple.get("user");
        String relation = (String) tuple.get("relation");
        String objectType = (String) tuple.get("objectType");
        String objectId = (String) tuple.get("objectId");
        if (tupleUserPrefixed == null || relation == null || objectType == null || objectId == null) {
            throw new IllegalStateException(
                    "outbox payload.tuple missing required keys: " + tuple);
        }
        // Strip the "user:" prefix so we can re-enter the existing
        // OpenFgaAuthzService API which prepends "user:" itself. This keeps
        // the V23 typed columns (which carry the "user:" prefix per Codex
        // contract) consistent with what hits OpenFGA.
        String userId = tupleUserPrefixed.startsWith("user:")
                ? tupleUserPrefixed.substring("user:".length())
                : tupleUserPrefixed;

        switch (entry.getAction()) {
            case GRANT -> authzService.writeTuple(userId, relation, objectType, objectId);
            case REVOKE -> authzService.deleteTuple(userId, relation, objectType, objectId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractTuple(Map<String, Object> payload) {
        if (payload == null) return Collections.emptyMap();
        Object tuple = payload.get("tuple");
        if (!(tuple instanceof Map<?, ?> map)) {
            throw new IllegalStateException("outbox payload.tuple missing or not an object: " + payload);
        }
        return (Map<String, Object>) map;
    }

    private void finalizeProcessed(DataAccessScopeOutboxEntry entry, Instant claimedLockedUntil) {
        Integer affected = txTemplate.execute(s ->
                outboxRepository.markProcessed(entry.getId(), Instant.now(), pollerId, claimedLockedUntil));
        if (affected != null && affected == 0) {
            log.warn("outbox CAS fence: stale worker for outbox_id={} action={} — finalize PROCESSED skipped (lock token mismatch)",
                    entry.getId(), entry.getAction());
            return;
        }
        log.info("outbox entry {} PROCESSED action={} scopeId={}",
                entry.getId(), entry.getAction(), entry.getScopeId());
    }

    private void scheduleRetry(DataAccessScopeOutboxEntry entry, Instant claimedLockedUntil, Throwable cause) {
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        int attempts = entry.getAttemptCount() == null ? 0 : entry.getAttemptCount();
        Instant nextAttempt = backoffPolicy.nextAttemptAt(
                attempts, config.getInitialBackoff(), config.getMaxBackoff());
        Integer affected = txTemplate.execute(s ->
                outboxRepository.markRetry(entry.getId(), nextAttempt, truncate(error), pollerId, claimedLockedUntil));
        if (affected != null && affected == 0) {
            log.warn("outbox CAS fence: stale worker for outbox_id={} retry — finalize skipped",
                    entry.getId());
            return;
        }
        log.warn("outbox entry {} retry scheduled action={} scopeId={} attempts={} nextAt={} reason={}",
                entry.getId(), entry.getAction(), entry.getScopeId(),
                attempts, nextAttempt, error);
    }

    private void markTerminalFailure(DataAccessScopeOutboxEntry entry, Instant claimedLockedUntil, Throwable cause) {
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        int attempts = entry.getAttemptCount() == null ? 0 : entry.getAttemptCount();
        Integer affected = txTemplate.execute(s ->
                outboxRepository.markFailed(entry.getId(), truncate(error), pollerId, claimedLockedUntil));
        if (affected != null && affected == 0) {
            log.warn("outbox CAS fence: stale worker for outbox_id={} terminal failure — finalize skipped",
                    entry.getId());
            return;
        }
        // Codex 019dd0e0 iter-2 MINOR — surface terminal FAILED so an operator
        // alert hook (Prometheus alert on this log level + scope_outbox view)
        // can pick it up without scraping the row directly.
        log.error("OUTBOX FAILED terminal: outbox_id={} action={} scopeId={} attempts={} reason={} — operator alert candidate",
                entry.getId(), entry.getAction(), entry.getScopeId(), attempts, error);
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() > 1024 ? error.substring(0, 1024) : error;
    }

    private static String resolvePollerId(Environment env) {
        String hostname = env.getProperty("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            return "permission-service-poller-unknown";
        }
        return hostname;
    }
}
