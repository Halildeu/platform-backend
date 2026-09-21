package com.example.meeting.controller;

import com.example.meeting.notify.MeetingReadyRecipients;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/meetings/{meetingId}/notification-recipients")
public class MeetingNotificationRecipientsController {
    private final MeetingReadyRecipients recipients;
    public MeetingNotificationRecipientsController(MeetingReadyRecipients recipients) { this.recipients = recipients; }
    public record Scope(@NotNull UUID tenantId, @NotNull UUID orgId) {}
    @PostMapping
    @PreAuthorize("hasAuthority('SVC_meeting:notification:read')")
    public List<Long> resolve(@PathVariable UUID meetingId, @Valid @RequestBody Scope scope) {
        return recipients.resolve(meetingId, scope.tenantId(), scope.orgId());
    }
}
