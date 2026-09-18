package com.example.meeting.notify;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingNotifyProperties;
import com.example.meeting.model.Meeting;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.service.AssigneeDirectoryClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MeetingReadyRecipientsTest {
    final UUID org = UUID.nameUUIDFromBytes("company:7".getBytes(StandardCharsets.UTF_8));
    final UUID meetingId = UUID.randomUUID();
    final MeetingRepository meetings = mock(MeetingRepository.class);
    final OpenFgaAuthzService auth = mock(OpenFgaAuthzService.class);
    final AssigneeDirectoryClient directory = mock(AssigneeDirectoryClient.class);
    MeetingReadyRecipients resolver;
    @BeforeEach void setup() {
        var properties = new MeetingNotifyProperties(); properties.setSubjectIssuer("issuer");
        ObjectProvider<OpenFgaAuthzService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auth); when(auth.isEnabled()).thenReturn(true);
        Meeting meeting = new Meeting(); meeting.setTenantId(org);
        when(meetings.findVisibleToOrgAndId(org, meetingId)).thenReturn(Optional.of(meeting));
        resolver = new MeetingReadyRecipients(meetings, provider, directory, properties);
        audience("owner", "subject-a", "10"); audience("participant", "subject-b"); audience("blocked");
        identity("subject-a", 10, true, false, 7L); identity("10", 10, true, false, 7L);
        identity("subject-b", 11, true, false, 7L);
    }
    void audience(String relation, String... ids) {
        when(auth.listUsers(relation, "meeting", meetingId.toString()))
            .thenReturn(new OpenFgaAuthzService.UserListResult(true, List.of(ids), "ok"));
    }
    void identity(String subject, long id, boolean enabled, boolean deleted, Long company) {
        when(directory.resolveNotificationIdentity("issuer", subject)).thenReturn(Optional.of(
            new AssigneeDirectoryClient.NotificationIdentity(id, enabled, deleted, company)));
    }
    @Test void canonicalAliasesDeduplicate() { assertThat(resolver.resolve(meetingId, org, org)).containsExactly(10L, 11L); }
    @Test void blockedStableAliasOverridesNumericOwner() {
        audience("owner", "10"); audience("blocked", "subject-a");
        assertThat(resolver.resolve(meetingId, org, org)).containsExactly(11L);
    }
    @Test void unavailableAuthorizationRetriesRatherThanEmptySuccess() {
        when(auth.listUsers("blocked", "meeting", meetingId.toString())).thenReturn(OpenFgaAuthzService.UserListResult.unavailable("down"));
        assertThatThrownBy(() -> resolver.resolve(meetingId, org, org)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(directory);
    }
    @Test void inactiveDeletedAndWrongOrgAreExcluded() {
        identity("subject-a", 10, false, false, 7L); identity("10", 10, false, false, 7L);
        identity("subject-b", 11, true, true, 7L);
        assertThat(resolver.resolve(meetingId, org, org)).isEmpty();
        identity("subject-b", 11, true, false, 8L);
        assertThat(resolver.resolve(meetingId, org, org)).isEmpty();
        identity("subject-b", 11, true, false, null);
        assertThat(resolver.resolve(meetingId, org, org)).isEmpty();
    }
    @Test void unresolvedBlockedAliasFailsClosed() {
        audience("blocked", "unknown"); when(directory.resolveNotificationIdentity("issuer", "unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(meetingId, org, org)).isInstanceOf(IllegalStateException.class);
    }
    @Test void foreignMeetingDoesNotEnumerateUsers() {
        when(meetings.findVisibleToOrgAndId(org, meetingId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(meetingId, org, org)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(auth, directory);
    }
}
