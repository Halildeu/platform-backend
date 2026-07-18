package com.example.meeting.service;

import com.example.meeting.config.MeetingSessionErasureProperties;
import com.example.meeting.model.MeetingSessionErasure;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lease-fenced coordinator worker. Logs contain counts/codes only, never aliases or payloads. */
@Component
@ConditionalOnProperty(name = "meeting.session-erasure.enabled", havingValue = "true")
public class MeetingSessionErasureWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeetingSessionErasureWorker.class);

    private final MeetingSessionErasureService service;
    private final TranscriptSessionErasureClient transcriptClient;
    private final MeetingSessionErasureProperties properties;
    private final String owner;

    public MeetingSessionErasureWorker(
            MeetingSessionErasureService service,
            TranscriptSessionErasureClient transcriptClient,
            MeetingSessionErasureProperties properties) {
        this.service = service;
        this.transcriptClient = transcriptClient;
        this.properties = properties;
        this.owner = properties.getOwner() == null || properties.getOwner().isBlank()
                ? deriveOwner() : properties.getOwner();
    }

    @Scheduled(fixedDelayString = "${meeting.session-erasure.poll-delay-ms:5000}")
    public void tick() {
        if (properties.isSchedulingEnabled()) {
            runCycle();
        }
    }

    public void runCycle() {
        int recovered = service.recoverStaleLeases();
        UUID claimToken = UUID.randomUUID();
        List<MeetingSessionErasure> claimed = service.claim(claimToken, owner);
        for (MeetingSessionErasure row : claimed) {
            process(row, claimToken);
        }
        if (recovered > 0 || !claimed.isEmpty()) {
            LOGGER.info("session erasure cycle recovered={} claimed={}", recovered, claimed.size());
        }
    }

    private void process(MeetingSessionErasure row, UUID claimToken) {
        try {
            TranscriptSessionErasureClient.Result preflight = transcriptClient.prepare(
                    row.getTenantId(), row.getMeetingId(), row.getSessionId(), row.getSourceSessionId());
            if (preflight.status() == TranscriptSessionErasureClient.Result.Status.HELD) {
                service.markRemoteHeld(row.getSessionId(), claimToken);
                return;
            }
            MeetingSessionErasureService.LocalResult local = service.eraseLocal(row, claimToken);
            if (local.status() != MeetingSessionErasureService.LocalResult.Status.READY) {
                return;
            }
            TranscriptSessionErasureClient.Result result = transcriptClient.erase(
                    local.tenantId(), local.meetingId(), local.sessionId(), local.sourceSessionId());
            if (result.status() == TranscriptSessionErasureClient.Result.Status.HELD) {
                service.markRemoteHeld(local.sessionId(), claimToken);
            } else {
                service.markComplete(local.sessionId(), claimToken, result.deletedCount());
            }
        } catch (HttpTranscriptSessionErasureClient.RemoteErasureException ex) {
            service.markFailure(row.getSessionId(), claimToken, ex.errorCode());
        } catch (RuntimeException ex) {
            service.markFailure(row.getSessionId(), claimToken, "REMOTE_FAILURE");
        }
    }

    private static String deriveOwner() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName()
                    + "-" + ProcessHandle.current().pid();
        } catch (Exception ignored) {
            return "meeting-session-erasure";
        }
    }
}
