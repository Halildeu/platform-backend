package com.example.meeting.events;

import static org.mockito.Mockito.*;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.notify.SummaryReadyNotificationSink;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MeetingReadyOutboxTest {
    @Test void summaryFailureDoesNotMarkEventPublished() {
        var repo=mock(MeetingEventOutboxRepository.class);
        var publisher=mock(MeetingEventPublisher.class);
        var sink=mock(SummaryReadyNotificationSink.class);
        ObjectProvider<SummaryReadyNotificationSink> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sink);
        var poller=new MeetingEventOutboxPoller(repo,publisher,null,10,30000,3,"test",false);
        poller.setSelf(poller); poller.setReadySink(provider);
        var row=mock(MeetingEventOutbox.class); var id=UUID.randomUUID(); var token=UUID.randomUUID();
        when(row.getId()).thenReturn(id); when(row.getClaimToken()).thenReturn(token);
        when(repo.claimBatch(any(),any(),eq("test"),any(),eq(10))).thenReturn(1);
        when(repo.findByClaimToken(any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("notify unavailable")).when(sink).deliver(any());
        poller.runCycle();
        verify(publisher).publish(any());
        verify(repo,never()).markPublishedFenced(any(),any(),any());
        verify(repo).markFailedFenced(eq(id),eq(token),eq("IllegalStateException"),eq(3),any());
    }
}
