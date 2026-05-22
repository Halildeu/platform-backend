package com.serban.notify.worker;

import com.serban.notify.fbl.FblService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Properties;

/**
 * FBL mailbox polling worker — Faz 23.8 M7 T4.3.5 (Codex 019e4edd
 * mailbox_pull_first_for_office365_fbl; backend-pr-fbl-mailbox-worker).
 *
 * <p>Office 365 Postmaster ARF (RFC 5965) spam-complaint reports are
 * delivered to an IMAP mailbox — there is no webhook push. This worker
 * periodically connects to that mailbox, hands each message to
 * {@link FblService#ingest} and, once a message has produced a terminal
 * {@code FblOutcome}, flags it {@code DELETED} so it is expunged on folder
 * close (delete-after-process).
 *
 * <p><b>Activation</b> (defer-aware, JetSmsDlrPollingWorker pattern): the
 * bean is created only when {@code notify.fbl.mailbox.enabled=true}
 * ({@code @ConditionalOnProperty}). Default off — the operator activates
 * after seeding the IMAP credentials (see RB-fbl-mailbox-activation.md).
 *
 * <p><b>Auth contract</b> (Codex 019e4ffd iter-2 MEDIUM): PR-1 implements
 * <b>basic IMAP auth only</b> ({@code username}/{@code password}). Exchange
 * Online disables IMAP Basic Auth per-tenant in many tenants, so Office 365
 * activation MUST be gated on a live "tenant IMAP AUTH succeeds" smoke
 * (RB-fbl-mailbox-activation.md) — this worker is not unconditionally
 * "Office 365 ready". {@code notify.fbl.mailbox.auth-mode} accepts only
 * {@code basic}; an XOAUTH2 token flow is a separate future PR and any
 * other value fails fast at bean creation.
 *
 * <p><b>Bounded I/O</b> (Codex 019e4ffd iter-2 HIGH): the IMAP store is
 * opened with explicit connect/read/write timeouts + TLS server-identity
 * verification so a network blackhole can never block the shared
 * {@code @Scheduled} thread indefinitely.
 *
 * <p><b>Multi-pod safety</b>: no IMAP-level locking is needed. If two pods
 * fetch the same message, {@code FblService} idempotency
 * ({@code email_bounce_event} unique {@code event_fingerprint}) makes the
 * second ingest a metric-only duplicate; the {@code DELETED} flag + expunge
 * is naturally idempotent.
 *
 * <p><b>Delete semantics</b>: a message is deleted once {@code ingest}
 * returns ANY {@code FblOutcome} (suppressed / duplicate / ignored /
 * unresolved / parse_error — all are "handled"; even an unparseable poison
 * message must be removed so it does not re-poll forever). A message is
 * <i>kept</i> only when {@code ingest} throws an unexpected exception
 * (transient failure → retried next cycle).
 */
@Component
@ConditionalOnProperty(name = "notify.fbl.mailbox.enabled", havingValue = "true")
public class FblMailboxPollingWorker {

    private static final Logger log = LoggerFactory.getLogger(FblMailboxPollingWorker.class);

    private final FblService fblService;

    private final String protocol;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String folderName;
    private final int batchSize;
    private final int connectionTimeoutMs;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;
    private final boolean schedulingEnabled;

    public FblMailboxPollingWorker(
        FblService fblService,
        @Value("${notify.fbl.mailbox.protocol:imaps}") String protocol,
        @Value("${notify.fbl.mailbox.host:}") String host,
        @Value("${notify.fbl.mailbox.port:993}") int port,
        @Value("${notify.fbl.mailbox.username:}") String username,
        @Value("${notify.fbl.mailbox.password:}") String password,
        @Value("${notify.fbl.mailbox.folder:INBOX}") String folderName,
        @Value("${notify.fbl.mailbox.batch-size:50}") int batchSize,
        @Value("${notify.fbl.mailbox.auth-mode:basic}") String authMode,
        @Value("${notify.fbl.mailbox.connection-timeout-ms:5000}") int connectionTimeoutMs,
        @Value("${notify.fbl.mailbox.read-timeout-ms:10000}") int readTimeoutMs,
        @Value("${notify.fbl.mailbox.write-timeout-ms:10000}") int writeTimeoutMs,
        @Value("${notify.fbl.mailbox.scheduling-enabled:true}") boolean schedulingEnabled
    ) {
        // Codex 019e4ffd iter-2 MEDIUM: auth contract explicit — only basic
        // IMAP auth is implemented in PR-1; fail fast on any other value so a
        // misconfigured XOAUTH2 expectation never silently degrades to basic.
        String normalizedAuthMode = authMode == null
            ? "basic" : authMode.trim().toLowerCase(Locale.ROOT);
        if (!"basic".equals(normalizedAuthMode)) {
            throw new IllegalStateException(
                "notify.fbl.mailbox.auth-mode='" + authMode + "' unsupported — "
                + "only 'basic' implemented (XOAUTH2 is a separate future PR)");
        }
        this.fblService = fblService;
        // Codex 019e4ffd iter-2 HIGH: normalise protocol (trim + lowercase)
        // so property keys (mail.<protocol>.*) are deterministic.
        this.protocol = (protocol == null || protocol.isBlank())
            ? "imaps" : protocol.trim().toLowerCase(Locale.ROOT);
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.folderName = folderName;
        this.batchSize = Math.max(batchSize, 1);
        this.connectionTimeoutMs = Math.max(connectionTimeoutMs, 1000);
        this.readTimeoutMs = Math.max(readTimeoutMs, 1000);
        this.writeTimeoutMs = Math.max(writeTimeoutMs, 1000);
        this.schedulingEnabled = schedulingEnabled;
        log.info("FblMailboxPollingWorker activated: protocol={} host={} port={} "
                + "folder={} batchSize={} authMode=basic connectTimeout={}ms "
                + "readTimeout={}ms scheduling={}",
            this.protocol, mask(host), port, folderName, this.batchSize,
            this.connectionTimeoutMs, this.readTimeoutMs, schedulingEnabled);
    }

    /**
     * Scheduled poll cycle. Default fixedDelay 120s, initialDelay 45s
     * ({@code notify.fbl.mailbox.poll-delay-ms} / {@code initial-delay-ms}).
     */
    @Scheduled(
        fixedDelayString = "${notify.fbl.mailbox.poll-delay-ms:120000}",
        initialDelayString = "${notify.fbl.mailbox.initial-delay-ms:45000}")
    public void tick() {
        if (!schedulingEnabled) {
            return;
        }
        runCycle();
    }

    /**
     * Public cycle entry — {@code @Scheduled} tick() or test direct call.
     * Connects, drains up to {@code batchSize} messages, expunges processed
     * ones. Cycle-level try/catch: a connect/fetch failure never kills the
     * scheduled task.
     *
     * @return number of messages successfully handed to FblService
     */
    public int runCycle() {
        if (isBlank(host) || isBlank(username)) {
            log.warn("FBL mailbox poll skipped: host/username not configured");
            return 0;
        }
        Store store = null;
        Folder folder = null;
        try {
            store = connectStore();
            folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            int processed = processFolder(folder);
            folder.close(true);   // expunge DELETED-flagged messages
            folder = null;
            if (processed > 0) {
                log.info("FblMailboxPollingWorker cycle: processed={}", processed);
            }
            return processed;
        } catch (Exception e) {
            log.warn("FblMailboxPollingWorker cycle error: {}", e.getMessage(), e);
            return 0;
        } finally {
            closeQuietly(folder, store);
        }
    }

    /** Open an IMAP(S) store connection. Package-private for test override. */
    Store connectStore() throws MessagingException {
        Session session = Session.getInstance(buildMailProperties());
        Store store = session.getStore(protocol);
        store.connect(host, port, username, password);
        return store;
    }

    /**
     * Build the JavaMail session properties (Codex 019e4ffd iter-2 HIGH):
     * bounded connect/read/write timeouts so a network blackhole can never
     * block the shared {@code @Scheduled} thread indefinitely, and — for an
     * SSL protocol — TLS server-identity verification.
     *
     * <p>Package-private so the property contract is unit-testable without a
     * live IMAP server.
     */
    Properties buildMailProperties() {
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".connectiontimeout",
            String.valueOf(connectionTimeoutMs));
        props.put("mail." + protocol + ".timeout",
            String.valueOf(readTimeoutMs));
        props.put("mail." + protocol + ".writetimeout",
            String.valueOf(writeTimeoutMs));
        if (protocol.endsWith("s")) {
            props.put("mail." + protocol + ".ssl.enable", "true");
            props.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        }
        return props;
    }

    /**
     * Process up to {@code batchSize} messages in an open folder.
     * Package-private — unit-tested directly with a mock {@link Folder}.
     *
     * @return count of messages handed to FblService (a terminal outcome)
     */
    int processFolder(Folder folder) throws MessagingException {
        Message[] messages = folder.getMessages();
        int limit = Math.min(messages.length, batchSize);
        int processed = 0;
        for (int i = 0; i < limit; i++) {
            Message message = messages[i];
            try {
                if (message instanceof MimeMessage mime) {
                    FblService.FblOutcome outcome = fblService.ingest(mime);
                    // Any terminal outcome → delete (poison parse_error too,
                    // otherwise it re-polls forever).
                    message.setFlag(Flags.Flag.DELETED, true);
                    processed++;
                    log.info("FBL mailbox message processed: outcome={}", outcome);
                } else {
                    // Non-MIME content cannot be an ARF report — remove it.
                    log.warn("FBL mailbox: non-MimeMessage encountered, deleting");
                    message.setFlag(Flags.Flag.DELETED, true);
                }
            } catch (RuntimeException | MessagingException e) {
                // Unexpected/transient failure — keep the message (not
                // DELETED-flagged) so the next cycle retries it.
                log.warn("FBL mailbox message error (kept for retry): {}",
                    e.getMessage(), e);
            }
        }
        return processed;
    }

    private static void closeQuietly(Folder folder, Store store) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (MessagingException ignored) {
                // best-effort
            }
        }
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException ignored) {
                // best-effort
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Mask a host for logs (keep TLD-ish tail, hide the rest). */
    private static String mask(String host) {
        if (isBlank(host)) {
            return "<unset>";
        }
        int dot = host.indexOf('.');
        return dot > 0 ? "***" + host.substring(dot) : "***";
    }
}
