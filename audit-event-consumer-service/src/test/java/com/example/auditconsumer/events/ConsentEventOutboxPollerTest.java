package com.example.auditconsumer.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.example.auditconsumer.model.ConsentEventOutbox;
import com.example.auditconsumer.repository.ConsentEventOutboxRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentEventOutboxPollerTest {

    @Test
    void committedClaimIsPublishedAndTokenFenced() {
        ConsentEventOutboxRepository repository = mock(ConsentEventOutboxRepository.class);
        ConsentEventPublisher publisher = mock(ConsentEventPublisher.class);
        ConsentEventOutboxPoller poller = new ConsentEventOutboxPoller(
                repository, publisher, 10, 60_000, 3, 5_000, "test-owner", false);
        poller.setSelf(poller);

        UUID id = UUID.randomUUID();
        UUID captureId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        ConsentEventOutbox row = row(id, captureId, claimToken);
        when(repository.recoverStaleLeases(any(), any(), eq(3))).thenReturn(0);
        when(repository.claimBatch(any(), any(), eq("test-owner"), any(), eq(10))).thenAnswer(invocation -> {
            UUID actualToken = invocation.getArgument(3);
            row.setClaimToken(actualToken);
            return 1;
        });
        when(repository.findByClaimToken(any())).thenReturn(List.of(row));
        when(repository.markPublishedFenced(eq(id), any(), any())).thenReturn(1);

        poller.runCycle();

        verify(publisher).publish(any(ConsentEventMessage.class));
        verify(repository).markPublishedFenced(eq(id), any(), any());
    }

    @Test
    void publishFailureReturnsRowToRetryWithTokenFence() {
        ConsentEventOutboxRepository repository = mock(ConsentEventOutboxRepository.class);
        ConsentEventPublisher publisher = mock(ConsentEventPublisher.class);
        ConsentEventOutboxPoller poller = new ConsentEventOutboxPoller(
                repository, publisher, 10, 60_000, 3, 5_000, "test-owner", false);
        poller.setSelf(poller);

        UUID id = UUID.randomUUID();
        ConsentEventOutbox row = row(id, UUID.randomUUID(), UUID.randomUUID());
        when(repository.claimBatch(any(), any(), anyString(), any(), anyInt())).thenAnswer(invocation -> {
            row.setClaimToken(invocation.getArgument(3));
            return 1;
        });
        when(repository.findByClaimToken(any())).thenReturn(List.of(row));
        when(repository.markFailedFenced(
                eq(id), any(), eq("IllegalStateException"), eq(3), any(), any()))
                .thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("transport unavailable"))
                .when(publisher).publish(any());

        poller.runCycle();

        verify(repository).markFailedFenced(
                eq(id), any(), eq("IllegalStateException"), eq(3), any(), any());
    }

    @Test
    void payloadHashMismatchFailsClosedBeforePublish() {
        ConsentEventOutbox row = row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        row.setPayloadHash("0".repeat(64));

        assertThatThrownBy(() -> ConsentEventMessage.from(row))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload hash mismatch");
    }

    @Test
    void publishSucceededButMarkLostMayRedeliverOnlyWithSameDeterministicEventKey() {
        ConsentEventOutboxRepository repository = mock(ConsentEventOutboxRepository.class);
        ConsentEventPublisher publisher = mock(ConsentEventPublisher.class);
        ConsentEventOutboxPoller poller = new ConsentEventOutboxPoller(
                repository, publisher, 10, 1, 3, 0, "test-owner", false);
        poller.setSelf(poller);

        UUID id = UUID.randomUUID();
        ConsentEventOutbox row = row(id, UUID.randomUUID(), UUID.randomUUID());
        when(repository.recoverStaleLeases(any(), any(), eq(3))).thenReturn(1);
        when(repository.claimBatch(any(), any(), eq("test-owner"), any(), eq(10)))
                .thenAnswer(invocation -> {
                    row.setClaimToken(invocation.getArgument(3));
                    return 1;
                });
        when(repository.findByClaimToken(any())).thenReturn(List.of(row));
        when(repository.markPublishedFenced(eq(id), any(), any())).thenReturn(0, 1);

        poller.runCycle();
        poller.runCycle();

        ArgumentCaptor<ConsentEventMessage> published =
                ArgumentCaptor.forClass(ConsentEventMessage.class);
        verify(publisher, times(2)).publish(published.capture());
        assertThat(published.getAllValues())
                .extracting(ConsentEventMessage::eventKey)
                .containsExactly(row.getEventKey(), row.getEventKey());
        assertThat(published.getAllValues())
                .extracting(ConsentEventMessage::payloadJson)
                .containsOnly(row.getPayload());
    }

    private static ConsentEventOutbox row(UUID id, UUID captureId, UUID claimToken) {
        ConsentEventOutbox row = new ConsentEventOutbox();
        row.setId(id);
        row.setEventKey("meeting.consent|" + captureId + "|meeting.consent.revoked|1");
        row.setEventType("meeting.consent.revoked");
        row.setAggregateId(captureId);
        row.setMeetingId(UUID.randomUUID());
        row.setTenantId(UUID.randomUUID());
        row.setOrgId(UUID.randomUUID());
        row.setPayload("{}");
        row.setPayloadHash(ConsentEventMessage.payloadHash(row.getPayload()));
        row.setClaimToken(claimToken);
        return row;
    }
}
