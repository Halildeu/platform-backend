package com.example.transcript.directstt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranscriptSessionAssociationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    private final TranscriptSessionAssociationStore store =
            mock(TranscriptSessionAssociationStore.class);
    private final MeetingSessionResolver resolver = mock(MeetingSessionResolver.class);
    private final DirectSttTranscriptResultConsumerProperties properties =
            new DirectSttTranscriptResultConsumerProperties();
    private final TranscriptSessionAssociation association = mock(TranscriptSessionAssociation.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private TranscriptSessionAssociationService service;

    @BeforeEach
    void setUp() {
        properties.getMapping().setMaxAttempts(3);
        properties.getMapping().setInitialBackoffMillis(100);
        properties.getMapping().setMaxBackoffMillis(1_000);
        when(association.getId()).thenReturn(UUID.randomUUID());
        when(association.getStatus()).thenReturn(TranscriptSessionAssociationStatus.PENDING);
        when(association.getResolutionAttempts()).thenReturn(0);
        when(store.ensurePending(any(), eq(TENANT), eq(MEETING), eq("SES-42"), eq(NOW)))
                .thenReturn(association);
        when(store.claim(eq(TENANT), eq(MEETING), eq("SES-42"), any(), eq(NOW), any()))
                .thenReturn(true);
        service = new TranscriptSessionAssociationService(
                store, resolver, properties, meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exactScopeResolutionPinsCanonicalUuid() {
        when(resolver.resolve(TENANT, MEETING, "SES-42"))
                .thenReturn(MeetingSessionResolution.resolved(
                        TENANT, TENANT, MEETING, SESSION, "SES-42"));
        when(store.completeResolved(eq(association.getId()), any(), eq(TENANT), eq(MEETING),
                eq("SES-42"), eq(SESSION), eq(NOW))).thenReturn(true);

        var outcome = service.resolve(TENANT, MEETING, "SES-42");

        assertThat(outcome.result()).isEqualTo(TranscriptSessionAssociationService.Result.RESOLVED);
        assertThat(outcome.sessionId()).isEqualTo(SESSION);
    }

    @Test
    void missingMappingSchedulesBoundedRetryInsteadOfSuccess() {
        TranscriptSessionAssociation pending = mock(TranscriptSessionAssociation.class);
        when(pending.getStatus()).thenReturn(TranscriptSessionAssociationStatus.PENDING);
        when(pending.getLastErrorCode()).thenReturn("MAPPING_NOT_FOUND");
        when(store.require(TENANT, MEETING, "SES-42")).thenReturn(pending);
        when(resolver.resolve(TENANT, MEETING, "SES-42"))
                .thenReturn(MeetingSessionResolution.failure(
                        MeetingSessionResolution.Status.NOT_FOUND, "MAPPING_NOT_FOUND"));

        var outcome = service.resolve(TENANT, MEETING, "SES-42");

        assertThat(outcome.result()).isEqualTo(TranscriptSessionAssociationService.Result.PENDING);
        assertThat(outcome.sessionId()).isNull();
        verify(store).fail(eq(association.getId()), any(), eq("MAPPING_NOT_FOUND"), eq(3),
                eq(false), eq(NOW.plusMillis(100)), eq(NOW));
    }

    @Test
    void scopeMismatchFailsClosedIntoDeadState() {
        TranscriptSessionAssociation dead = mock(TranscriptSessionAssociation.class);
        when(dead.getStatus()).thenReturn(TranscriptSessionAssociationStatus.DEAD);
        when(dead.getLastErrorCode()).thenReturn("MAPPING_SCOPE_MISMATCH");
        when(store.require(TENANT, MEETING, "SES-42")).thenReturn(dead);
        when(resolver.resolve(TENANT, MEETING, "SES-42"))
                .thenReturn(MeetingSessionResolution.resolved(
                        UUID.randomUUID(), TENANT, MEETING, SESSION, "SES-42"));

        var outcome = service.resolve(TENANT, MEETING, "SES-42");

        assertThat(outcome.result()).isEqualTo(TranscriptSessionAssociationService.Result.DEAD);
        verify(store).fail(eq(association.getId()), any(), eq("MAPPING_SCOPE_MISMATCH"), eq(3),
                eq(true), eq(null), eq(NOW));
    }

    @Test
    void existingResolvedAssociationIsAnIdempotentLocalReplay() {
        when(association.getStatus()).thenReturn(TranscriptSessionAssociationStatus.RESOLVED);
        when(association.getSessionId()).thenReturn(SESSION);

        var outcome = service.resolve(TENANT, MEETING, "SES-42");

        assertThat(outcome.result()).isEqualTo(TranscriptSessionAssociationService.Result.RESOLVED);
        assertThat(outcome.sessionId()).isEqualTo(SESSION);
        verify(resolver, never()).resolve(any(), any(), any());
        verify(store, never()).claim(any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiredClaimStaysRetryableWithoutConsumingFailureBudget() {
        TranscriptSessionAssociation stale = mock(TranscriptSessionAssociation.class);
        TranscriptSessionAssociation pending = mock(TranscriptSessionAssociation.class);
        when(stale.getStatus()).thenReturn(TranscriptSessionAssociationStatus.RESOLVING);
        when(stale.getResolutionAttempts()).thenReturn(2);
        when(store.ensurePending(any(), eq(TENANT), eq(MEETING), eq("SES-42"), eq(NOW)))
                .thenReturn(stale);
        when(store.recoverStale(
                TENANT, MEETING, "SES-42", NOW.plusMillis(400), NOW))
                .thenReturn(true);
        when(pending.getStatus()).thenReturn(TranscriptSessionAssociationStatus.PENDING);
        when(pending.getLastErrorCode()).thenReturn("LEASE_EXPIRED");
        when(store.require(TENANT, MEETING, "SES-42")).thenReturn(pending);

        var outcome = service.resolve(TENANT, MEETING, "SES-42");

        assertThat(outcome.result()).isEqualTo(TranscriptSessionAssociationService.Result.PENDING);
        assertThat(outcome.reasonCode()).isEqualTo("LEASE_EXPIRED");
        verify(resolver, never()).resolve(any(), any(), any());
        verify(store, never()).claim(any(), any(), any(), any(), any(), any());
    }
}
