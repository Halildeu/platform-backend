package com.example.meeting.notify;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class NotificationDeliveryQueueTest {
    private final MeetingEventOutboxRepository repository = mock(MeetingEventOutboxRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final AssignmentNotificationSink sink = mock(AssignmentNotificationSink.class);
    private NotificationDeliveryQueue queue;
    private final UUID id = UUID.randomUUID();
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        queue = new NotificationDeliveryQueue(repository, manager, sink, mock(org.springframework.beans.factory.ObjectProvider.class), false, 20);
        var row = mock(MeetingEventOutbox.class); when(row.getId()).thenReturn(id);
        when(repository.lockNextNotification(false)).thenReturn(Optional.of(row));
        when(sink.handles(any())).thenReturn(true);
    }
    @Test void httpFailureChangesOnlyNotificationState() {
        doThrow(new IllegalStateException("offline")).when(sink).deliver(any());
        queue.runOne(); queue.runOne();
        verify(repository, times(2)).notificationFailed(eq(id), eq(20), any(), eq("IllegalStateException"));
        verify(repository, times(2)).lockNextNotification(false);
        verifyNoMoreInteractions(repository);
    }
    @Test void successfulRetryCompletesIndependentJob() {
        queue.runOne();
        verify(repository).lockNextNotification(false); verify(repository).notificationDelivered(id);
        verifyNoMoreInteractions(repository);
    }
    @Test void noJobMakesNoHttpRequest() {
        when(repository.lockNextNotification(false)).thenReturn(Optional.empty());
        queue.runOne(); verify(sink, never()).deliver(any());
    }
    @Test void handoffOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> queue.enqueue(id)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }
}