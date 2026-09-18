package com.example.meeting.notify;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingNotifyProperties;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.service.AssigneeDirectoryClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Resolves current effective owners/participants, with deny winning across identity aliases. */
@Service
public class MeetingReadyRecipients {
    private final MeetingRepository meetings;
    private final ObjectProvider<OpenFgaAuthzService> authorization;
    private final AssigneeDirectoryClient directory;
    private final MeetingNotifyProperties properties;
    public MeetingReadyRecipients(MeetingRepository meetings, ObjectProvider<OpenFgaAuthzService> authorization,
            AssigneeDirectoryClient directory, MeetingNotifyProperties properties) {
        this.meetings = meetings; this.authorization = authorization;
        this.directory = directory; this.properties = properties;
    }

    public List<Long> resolve(UUID meetingId, UUID tenantId, UUID orgId) {
        if (meetingId == null || tenantId == null || !tenantId.equals(orgId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification scope");
        var meeting = meetings.findVisibleToOrgAndId(orgId, meetingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenantId.equals(meeting.getTenantId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        OpenFgaAuthzService auth = authorization.getIfAvailable();
        if (auth == null || !auth.isEnabled()) throw new IllegalStateException("notification authorization unavailable");
        Set<String> candidates = new TreeSet<>(subjects(auth, "owner", meetingId));
        candidates.addAll(subjects(auth, "participant", meetingId));
        Set<String> blocked = new TreeSet<>(subjects(auth, "blocked", meetingId));
        if (candidates.size() + blocked.size() > 1000) throw new IllegalStateException("notification audience exceeds bound");
        Map<String, Optional<AssigneeDirectoryClient.NotificationIdentity>> identities = new HashMap<>();
        Set<String> all = new TreeSet<>(candidates); all.addAll(blocked);
        for (String subject : all) identities.put(subject,
                directory.resolveNotificationIdentity(properties.getSubjectIssuer(), subject));
        Set<Long> denied = new HashSet<>();
        for (String subject : blocked) {
            // A missing blocked alias cannot be treated as proof that nobody is denied.
            var identity = identities.get(subject).orElseThrow(() -> new IllegalStateException("blocked identity unresolved"));
            denied.add(identity.userId());
        }
        Set<Long> result = new TreeSet<>();
        for (String subject : candidates) identities.get(subject).ifPresent(identity -> {
            if (!identity.enabled() || identity.deleted() || identity.companyId() == null || identity.companyId() <= 0) return;
            UUID company = UUID.nameUUIDFromBytes(("company:" + identity.companyId()).getBytes(StandardCharsets.UTF_8));
            if (orgId.equals(company) && !denied.contains(identity.userId())) result.add(identity.userId());
        });
        return List.copyOf(result);
    }
    private List<String> subjects(OpenFgaAuthzService auth, String relation, UUID meetingId) {
        var answer = auth.listUsers(relation, "meeting", meetingId.toString());
        if (!answer.available()) throw new IllegalStateException("notification authorization unavailable");
        return answer.subjects();
    }
}
