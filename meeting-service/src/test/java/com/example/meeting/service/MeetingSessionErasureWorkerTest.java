package com.example.meeting.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.meeting.config.MeetingSessionErasureProperties;
import com.example.meeting.model.MeetingSessionErasure;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeetingSessionErasureWorkerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    @Mock private MeetingSessionErasureService service;
    @Mock private TranscriptSessionErasureClient transcriptClient;

    private MeetingSessionErasureWorker worker;

    @BeforeEach
    void setUp() {
        MeetingSessionErasureProperties properties = new MeetingSessionErasureProperties();
        properties.setEnabled(true);
        properties.setOwner("test-worker");
        worker = new MeetingSessionErasureWorker(service, transcriptClient, properties);
    }

    @Test
    void cycleClaimsErasesRemoteAndCompletesWithTheSameLeaseToken() {
        MeetingSessionErasure row = new MeetingSessionErasure();
        row.setSessionId(SESSION);
        when(service.recoverStaleLeases()).thenReturn(0);
        when(service.claim(any(UUID.class), eq("test-worker"))).thenReturn(List.of(row));
        when(service.eraseLocal(eq(row), any(UUID.class))).thenReturn(
                MeetingSessionErasureService.LocalResult.ready(
                        TENANT, MEETING, SESSION, "source-session"));
        when(transcriptClient.erase(TENANT, MEETING, SESSION, "source-session"))
                .thenReturn(new TranscriptSessionErasureClient.Result(
                        TranscriptSessionErasureClient.Result.Status.COMPLETE, 4));

        worker.runCycle();

        ArgumentCaptor<UUID> claim = ArgumentCaptor.forClass(UUID.class);
        verify(service).claim(claim.capture(), eq("test-worker"));
        verify(service).eraseLocal(row, claim.getValue());
        verify(service).markComplete(SESSION, claim.getValue(), 4);
    }

    @Test
    void remoteHoldMovesClaimToHeldInsteadOfCompleting() {
        MeetingSessionErasure row = new MeetingSessionErasure();
        row.setSessionId(SESSION);
        when(service.claim(any(UUID.class), eq("test-worker"))).thenReturn(List.of(row));
        when(service.eraseLocal(eq(row), any(UUID.class))).thenReturn(
                MeetingSessionErasureService.LocalResult.ready(
                        TENANT, MEETING, SESSION, "source-session"));
        when(transcriptClient.erase(TENANT, MEETING, SESSION, "source-session"))
                .thenReturn(new TranscriptSessionErasureClient.Result(
                        TranscriptSessionErasureClient.Result.Status.HELD, 0));

        worker.runCycle();

        verify(service).markRemoteHeld(eq(SESSION), any(UUID.class));
    }
}
