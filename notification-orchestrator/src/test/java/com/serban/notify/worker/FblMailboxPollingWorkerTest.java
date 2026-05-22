package com.serban.notify.worker;

import com.serban.notify.fbl.FblService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FblMailboxPollingWorker} — the IMAP mailbox poll
 * processing logic (Faz 23.8 M7 T4.3.5 FBL backend-pr-fbl-mailbox-worker).
 *
 * <p>Covers {@code processFolder} (the message-processing loop) with a mock
 * {@link Folder} + mock messages, and the {@code runCycle} no-config guard.
 * The IMAP {@code connectStore} network plumbing is exercised by the
 * operator live smoke (RB-fbl-mailbox-activation.md), not here.
 */
class FblMailboxPollingWorkerTest {

    private FblService fblService;

    @BeforeEach
    void setUp() {
        fblService = mock(FblService.class);
    }

    private FblMailboxPollingWorker worker(int batchSize) {
        return new FblMailboxPollingWorker(
            fblService, "imaps", "imap.example.com", 993,
            "fbl@acik.com", "secret", "INBOX", batchSize,
            "basic", 5000, 10000, 10000, false);
    }

    @Test
    void processFolderIngestsAndDeletesMimeMessage() throws Exception {
        FblMailboxPollingWorker w = worker(50);
        Folder folder = mock(Folder.class);
        MimeMessage message = mock(MimeMessage.class);
        when(folder.getMessages()).thenReturn(new Message[]{message});
        when(fblService.ingest(message)).thenReturn(FblService.FblOutcome.SUPPRESSED);

        int processed = w.processFolder(folder);

        assertThat(processed).isEqualTo(1);
        verify(fblService).ingest(message);
        verify(message).setFlag(Flags.Flag.DELETED, true);
    }

    @Test
    void parseErrorOutcomeStillDeletesMessage() throws Exception {
        // A poison/unparseable message must be removed (delete-after-process
        // on ANY terminal outcome) so it does not re-poll forever.
        FblMailboxPollingWorker w = worker(50);
        Folder folder = mock(Folder.class);
        MimeMessage message = mock(MimeMessage.class);
        when(folder.getMessages()).thenReturn(new Message[]{message});
        when(fblService.ingest(message)).thenReturn(FblService.FblOutcome.PARSE_ERROR);

        int processed = w.processFolder(folder);

        assertThat(processed).isEqualTo(1);
        verify(message).setFlag(Flags.Flag.DELETED, true);
    }

    @Test
    void ingestExceptionKeepsMessageForRetry() throws Exception {
        // An unexpected exception is transient — the message must NOT be
        // deleted so the next cycle retries it.
        FblMailboxPollingWorker w = worker(50);
        Folder folder = mock(Folder.class);
        MimeMessage message = mock(MimeMessage.class);
        when(folder.getMessages()).thenReturn(new Message[]{message});
        when(fblService.ingest(message)).thenThrow(new RuntimeException("transient DB error"));

        int processed = w.processFolder(folder);

        assertThat(processed).isZero();
        verify(message, never()).setFlag(any(Flags.Flag.class), anyBoolean());
    }

    @Test
    void batchSizeLimitsProcessing() throws Exception {
        FblMailboxPollingWorker w = worker(2);
        Folder folder = mock(Folder.class);
        MimeMessage m0 = mock(MimeMessage.class);
        MimeMessage m1 = mock(MimeMessage.class);
        MimeMessage m2 = mock(MimeMessage.class);
        when(folder.getMessages()).thenReturn(new Message[]{m0, m1, m2});
        when(fblService.ingest(any())).thenReturn(FblService.FblOutcome.DUPLICATE);

        int processed = w.processFolder(folder);

        assertThat(processed).isEqualTo(2);
        verify(fblService).ingest(m0);
        verify(fblService).ingest(m1);
        verify(fblService, never()).ingest(m2);
        verify(m2, never()).setFlag(any(Flags.Flag.class), anyBoolean());
    }

    @Test
    void nonMimeMessageDeletedWithoutIngest() throws Exception {
        FblMailboxPollingWorker w = worker(50);
        Folder folder = mock(Folder.class);
        Message nonMime = mock(Message.class);   // abstract Message, not MimeMessage
        when(folder.getMessages()).thenReturn(new Message[]{nonMime});

        int processed = w.processFolder(folder);

        assertThat(processed).isZero();
        verify(fblService, never()).ingest(any());
        verify(nonMime).setFlag(Flags.Flag.DELETED, true);
    }

    @Test
    void runCycleSkipsWhenHostNotConfigured() {
        FblMailboxPollingWorker w = new FblMailboxPollingWorker(
            fblService, "imaps", "", 993, "", "", "INBOX", 50,
            "basic", 5000, 10000, 10000, false);

        assertThat(w.runCycle()).isZero();
        verifyNoInteractions(fblService);
    }

    @Test
    void buildMailPropertiesHasBoundedTimeoutsAndTlsVerification() {
        // Codex 019e4ffd iter-2 HIGH: a network blackhole must never block
        // the shared @Scheduled thread — connect/read/write timeouts bounded.
        Properties props = worker(50).buildMailProperties();

        assertThat(props.getProperty("mail.imaps.connectiontimeout")).isEqualTo("5000");
        assertThat(props.getProperty("mail.imaps.timeout")).isEqualTo("10000");
        assertThat(props.getProperty("mail.imaps.writetimeout")).isEqualTo("10000");
        assertThat(props.getProperty("mail.imaps.ssl.enable")).isEqualTo("true");
        assertThat(props.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(props.getProperty("mail.store.protocol")).isEqualTo("imaps");
    }

    @Test
    void nonBasicAuthModeFailsFast() {
        // Codex 019e4ffd iter-2 MEDIUM: only basic auth implemented — any
        // other auth-mode must fail fast at bean creation, never silently
        // degrade to basic.
        assertThatThrownBy(() -> new FblMailboxPollingWorker(
            fblService, "imaps", "imap.example.com", 993,
            "fbl@acik.com", "secret", "INBOX", 50,
            "xoauth2", 5000, 10000, 10000, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth-mode");
    }
}
