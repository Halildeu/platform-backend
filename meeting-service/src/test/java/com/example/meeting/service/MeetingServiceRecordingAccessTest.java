package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingCreateRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeetingServiceRecordingAccessTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "stable-sub-3", "legacy-user-3");

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private MeetingSessionRepository sessionRepository;
    @Mock
    private MeetingActionRepository actionRepository;
    @Mock
    private MeetingDecisionRepository decisionRepository;
    @Mock
    private ObjectProvider<OpenFgaAuthzService> authzProvider;
    @Mock
    private OpenFgaAuthzService authzService;

    private MeetingService meetingService;

    @BeforeEach
    void setUp() {
        meetingService = new MeetingService(
                meetingRepository,
                sessionRepository,
                actionRepository,
                decisionRepository,
                authzProvider,
                false,
                false);
    }

    @Test
    void requireRecordingAccessAllowsWhenCanRecordRelationAllows() {
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meetingCreatedBy("stable-sub-3")));
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(true);

        meetingService.requireRecordingAccess(TENANT, MEETING_ID);

        verify(authzService).checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    @Test
    void requireRecordingAccessDoesNotDoublePrefixPrincipal() {
        AdminTenantContext prefixedTenant =
                new AdminTenantContext(TENANT_ID, "user:stable-sub-3", "user:legacy-user-3");
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(true);

        meetingService.requireRecordingAccess(prefixedTenant, MEETING_ID);

        verify(authzService).checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    @Test
    void requireRecordingAccessDeniesWhenCanRecordRelationDenies() {
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meetingCreatedBy("stable-sub-3")));
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(false);

        assertThatThrownBy(() -> meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requireRecordingAccessFailsClosedWhenOpenFgaBeanIsMissing() {
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meetingCreatedBy("stable-sub-3")));
        when(authzProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(authzService);
    }

    @Test
    void requireRecordingAccessFailsClosedWhenOpenFgaIsDisabled() {
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meetingCreatedBy("stable-sub-3")));
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(authzService, never()).checkPrincipal(
                any(), any(), any(), any());
    }

    @Test
    void requireRecordingAccessDeniesBlankAuthzPrincipalBeforeObjectAuthz() {
        AdminTenantContext blankPrincipalTenant = new AdminTenantContext(TENANT_ID, " ", "legacy-user-3");
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));

        assertThatThrownBy(() -> meetingService.requireRecordingAccess(blankPrincipalTenant, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(authzProvider, authzService);
    }

    @Test
    void requireRecordingAccessReturns404BeforeObjectAuthzForInvisibleMeeting() {
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(authzProvider, authzService);
    }

    @Test
    void legacyFallbackAllowsOnlyCreatorWhenStablePrincipalIsNotBlocked() {
        meetingService = transitionalMeetingService();
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meetingCreatedBy("stable-sub-3")));
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(false);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.BLOCKED, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(false);
        when(authzService.checkPrincipal(
                "user:legacy-user-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(true);

        meetingService.requireRecordingAccess(TENANT, MEETING_ID);

        verify(authzService).checkPrincipal(
                "user:legacy-user-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    @Test
    void legacyFallbackCannotCrossCreatorSubjectBoundary() {
        meetingService = transitionalMeetingService();
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meetingCreatedBy("different-stable-sub")));
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(false);

        assertThatThrownBy(() -> meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(authzService, never()).checkPrincipal(
                "user:legacy-user-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    @Test
    void stableBlockedRelationPreventsLegacyFallback() {
        meetingService = transitionalMeetingService();
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meetingCreatedBy("stable-sub-3")));
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(false);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.BLOCKED, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(true);

        assertThatThrownBy(() -> meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(authzService, never()).checkPrincipal(
                "user:legacy-user-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    @Test
    void legacyFallbackDeniesWhenLegacyCanRecordIsDeniedByModel() {
        meetingService = transitionalMeetingService();
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meetingCreatedBy("stable-sub-3")));
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(false);
        when(authzService.checkPrincipal(
                "user:stable-sub-3", MeetingAuthz.BLOCKED, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(false);
        when(authzService.checkPrincipal(
                "user:legacy-user-3", MeetingAuthz.CAN_RECORD, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString()))
                .thenReturn(false);

        assertThatThrownBy(() -> meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createDualWritesStableAndLegacyOwnerTuplesDuringRollbackWindow() {
        meetingService = transitionalMeetingService();
        Meeting saved = mock(Meeting.class);
        when(saved.getId()).thenReturn(MEETING_ID);
        when(meetingRepository.save(any(Meeting.class))).thenReturn(saved);
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);

        meetingService.createMeeting(TENANT,
                new MeetingCreateRequest("Title", null, null, null, null));

        verify(authzService).writeTuple(
                "stable-sub-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
        verify(authzService).writeTuple(
                "legacy-user-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    @Test
    void createCompensatesStableTupleWhenLegacyDualWriteFails() {
        meetingService = transitionalMeetingService();
        Meeting saved = mock(Meeting.class);
        when(saved.getId()).thenReturn(MEETING_ID);
        when(meetingRepository.save(any(Meeting.class))).thenReturn(saved);
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        doNothing().when(authzService).writeTuple(
                "stable-sub-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
        doThrow(new RuntimeException("legacy write failed"))
                .when(authzService).writeTuple(
                        "legacy-user-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());

        assertThatThrownBy(() -> meetingService.createMeeting(TENANT,
                new MeetingCreateRequest("Title", null, null, null, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("legacy write failed");

        verify(authzService).deleteTuple(
                "stable-sub-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    @Test
    void createWritesOnlyStableOwnerWhenMigrationWindowIsClosed() {
        Meeting saved = mock(Meeting.class);
        when(saved.getId()).thenReturn(MEETING_ID);
        when(meetingRepository.save(any(Meeting.class))).thenReturn(saved);
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);

        meetingService.createMeeting(TENANT,
                new MeetingCreateRequest("Title", null, null, null, null));

        verify(authzService).writeTuple(
                "stable-sub-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
        verify(authzService, never()).writeTuple(
                "legacy-user-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    @Test
    void createNormalizesPrincipalPrefixAndSkipsEquivalentLegacyDualWrite() {
        meetingService = transitionalMeetingService();
        AdminTenantContext equivalentPrincipals =
                new AdminTenantContext(TENANT_ID, "user:stable-sub-3", "stable-sub-3");
        Meeting saved = mock(Meeting.class);
        when(saved.getId()).thenReturn(MEETING_ID);
        when(meetingRepository.save(any(Meeting.class))).thenReturn(saved);
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);

        meetingService.createMeeting(equivalentPrincipals,
                new MeetingCreateRequest("Title", null, null, null, null));

        verify(authzService).writeTuple(
                "stable-sub-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
        verify(authzService, never()).writeTuple(
                "user:stable-sub-3", MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, MEETING_ID.toString());
    }

    private MeetingService transitionalMeetingService() {
        return new MeetingService(
                meetingRepository, sessionRepository, actionRepository, decisionRepository,
                authzProvider, true, true);
    }

    private static Meeting meetingCreatedBy(String subject) {
        Meeting meeting = new Meeting();
        meeting.setCreatedBySubject(subject);
        return meeting;
    }
}
