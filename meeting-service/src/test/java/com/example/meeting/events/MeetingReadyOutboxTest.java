package com.example.meeting.events;

import static org.mockito.Mockito.*;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.notify.NotificationDeliveryQueue;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MeetingReadyOutboxTest {
    @Test void successfulDomainDeliveryEnqueuesWithoutHttp() {
        var repo=mock(MeetingEventOutboxRepository.class);
        var publisher=mock(MeetingEventPublisher.class);
        var sink=mock(NotificationDeliveryQueue.class);
        ObjectProvider<NotificationDeliveryQueue> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sink);
        var poller=new MeetingEventOutboxPoller(repo,publisher,null,10,30000,3,"test",false);
        poller.setSelf(poller); poller.setDeliveryQueue(provider);
        var row=mock(MeetingEventOutbox.class); var id=UUID.randomUUID(); var token=UUID.randomUUID();
        when(row.getId()).thenReturn(id); when(row.getClaimToken()).thenReturn(token);
        when(repo.claimBatch(any(),any(),eq("test"),any(),eq(10))).thenReturn(1);
        when(repo.findByClaimToken(any())).thenReturn(List.of(row));
        when(repo.markPublishedFenced(eq(id),eq(token),any())).thenReturn(1);
        poller.runCycle();
        verify(publisher).publish(any());
        verify(sink).enqueue(id); verify(sink,never()).runOne();
        verify(repo,never()).markFailedFenced(any(),any(),any(),anyInt(),any());
    }
    @Test void lostFenceNeverEnqueuesNotification() {
        var repo = mock(MeetingEventOutboxRepository.class);
        var queue = mock(NotificationDeliveryQueue.class);
        ObjectProvider<NotificationDeliveryQueue> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(queue);
        var poller = new MeetingEventOutboxPoller(repo, mock(MeetingEventPublisher.class), null, 10, 30000, 3, "test", false);
        poller.setDeliveryQueue(provider);
        poller.markPublished(UUID.randomUUID(), UUID.randomUUID());
        verifyNoInteractions(queue);
    }}
