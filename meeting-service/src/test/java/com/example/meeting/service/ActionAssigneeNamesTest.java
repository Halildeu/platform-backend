package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.meeting.dto.v1.admin.MeetingActionResponse;
import com.example.meeting.model.MeetingActionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * gitops#3834 — the task list must show WHO a task is assigned to, not a Keycloak UUID; and a
 * directory hiccup must never take the task list down with it.
 */
@ExtendWith(MockitoExtension.class)
class ActionAssigneeNamesTest {

    @Mock private AssigneeDirectoryClient directory;

    private static MeetingActionResponse action(String assigneeSubject) {
        return new MeetingActionResponse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Bütçe tablosunu kontrol et", assigneeSubject, null, MeetingActionStatus.OPEN, null,
                "system:meeting-ai", Instant.EPOCH, "system:meeting-ai", Instant.EPOCH, 0L);
    }

    @Test
    void namesEachAssignedRowWithOneDirectoryCall() {
        when(directory.resolveDisplayNames(Set.of("kc-7", "kc-9")))
                .thenReturn(Map.of("kc-7", "Sevil Kaya", "kc-9", "Mehmet Demir"));

        List<MeetingActionResponse> named = new ActionAssigneeNames(directory)
                .withNames(List.of(action("kc-7"), action(null), action("kc-9"), action("kc-7")));

        assertThat(named).extracting(MeetingActionResponse::assigneeDisplayName)
                .containsExactly("Sevil Kaya", null, "Mehmet Demir", "Sevil Kaya");
        verify(directory).resolveDisplayNames(Set.of("kc-7", "kc-9"));
    }

    @Test
    void unknownSubjectStaysUnnamed() {
        when(directory.resolveDisplayNames(Set.of("kc-gone"))).thenReturn(Map.of());

        assertThat(new ActionAssigneeNames(directory).withName(action("kc-gone")).assigneeDisplayName()).isNull();
    }

    @Test
    void directoryOutageReturnsTheRowsUnnamedInsteadOfFailing() {
        when(directory.resolveDisplayNames(anyCollection()))
                .thenThrow(new AssigneeDirectoryClient.ResolutionUnavailableException("down"));

        List<MeetingActionResponse> rows = List.of(action("kc-7"));
        assertThat(new ActionAssigneeNames(directory).withNames(rows)).isSameAs(rows);
    }

    @Test
    void unassignedOrNonDirectorySubjectsNeverReachTheDirectory() {
        String tooLong = "x".repeat(65);
        List<MeetingActionResponse> rows = List.of(action(null), action(" "), action(tooLong));

        assertThat(new ActionAssigneeNames(directory).withNames(rows)).isSameAs(rows);
        verifyNoInteractions(directory);
    }
}
