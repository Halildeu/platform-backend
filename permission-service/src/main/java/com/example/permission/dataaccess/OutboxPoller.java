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
 * Faz 21.3 PR-G — outbox poller. Codex 019dcf5c iter-2 strategic primary.
 *
 * <p>Per-tick lifecycle (default 5s, configurable via
 * {@code app.outbox.poll-interval-ms}):
 * <ol>
 *   <li>{@link DataAccessScopeOutboxRepository#recoverStuckRows()} — releases
 *       PROCESSING rows whose lock expired (pod crash / hang) back to PENDING.</li>
 *   <li>{@link DataAccessScopeOutboxRepository#claimBatch} — atomically flips a
 *       batch of PENDING rows to PROCESSING (FOR UPDATE SKIP LOCKED, ordered
 *       per-scope to prevent GRANT/REVOKE race).</li>
 *   <li>For each claimed entry, the OpenFGA write is performed
 *       <strong>OUTSIDE the claim TX</strong>; the outcome (PROCESSED, retry,
 *       FAILED terminal) is persisted in a fresh per-entry TX. Slow FGA calls
 *       therefore never hold the outbox row's PG lock.</li>
 * </ol>
 *
 * <p>Spring AOP self-invocation trap is avoided by using
 * {@link TransactionTemplate} explicitly rather than annotating internal
 * methods with {@code @Transactional}: the scheduler entry point
 * {@code pollAndProcess} would otherwise call {@code processEntry} via
 * {@code this} and bypass the proxy.
 *
 * <p>Activation gate identical to {@link AccessScopeService}: multi-name
 * {@code @ConditionalOnProperty} (reports-db.enabled + erp.openfga.enabled).
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
            processEntry(entry);
        }
    }

    /**
     * Visible for tests — exercised directly so the test does not have to
     * stand up a full {@link Scheduled} thread.
     */
    void processEntry(DataAccessScopeOutboxEntry entry) {
        try {
            invokeFga(entry);
            txTemplate.executeWithoutResult(s -> {
                entry.markProcessed();
                outboxRepository.save(entry);
            });
            log.info("outbox entry {} PROCESSED action={} scopeId={}",
                    entry.getId(), entry.getAction(), entry.getScopeId());
        } catch (Exception e) {
            txTemplate.executeWithoutResult(s -> handleFailure(entry, e));
        }
    }

    private void invokeFga(DataAccessScopeOutboxEntry entry) {
        Map<String, Object> tuple = extractTuple(entry.getPayload());
        String userId = (String) tuple.get("user");
        String relation = (String) tuple.get("relation");
        String objectType = (String) tuple.get("objectType");
        String objectId = (String) tuple.get("objectId");
        if (userId == null || relation == null || objectType == null || objectId == null) {
            throw new IllegalStateException(
                    "outbox payload.tuple missing required keys: " + tuple);
        }
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

    private void handleFailure(DataAccessScopeOutboxEntry entry, Exception cause) {
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        int attempts = entry.getAttemptCount() == null ? 0 : entry.getAttemptCount();
        if (attempts >= config.getMaxAttempts()) {
            entry.markFailedTerminal(error);
            outboxRepository.save(entry);
            log.error("outbox entry {} FAILED terminal action={} scopeId={} attempts={} reason={}",
                    entry.getId(), entry.getAction(), entry.getScopeId(), attempts, error);
            return;
        }
        Instant nextAttempt = backoffPolicy.nextAttemptAt(
                attempts, config.getInitialBackoff(), config.getMaxBackoff());
        entry.scheduleRetry(error, nextAttempt);
        outboxRepository.save(entry);
        log.warn("outbox entry {} retry scheduled action={} scopeId={} attempts={} nextAt={} reason={}",
                entry.getId(), entry.getAction(), entry.getScopeId(),
                attempts, nextAttempt, error);
    }

    private static String resolvePollerId(Environment env) {
        String hostname = env.getProperty("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            return "permission-service-poller-unknown";
        }
        return hostname;
    }
}
