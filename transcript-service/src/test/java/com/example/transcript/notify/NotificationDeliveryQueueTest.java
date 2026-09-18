package com.example.transcript.notify;
import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class NotificationDeliveryQueueTest {
    private final TranscriptEventOutboxRepository repository = mock(TranscriptEventOutboxRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TranscriptReadyNotificationSink sink = mock(TranscriptReadyNotificationSink.class);
    private NotificationDeliveryQueue queue;
    private final UUID id = UUID.randomUUID();
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        queue = new NotificationDeliveryQueue(repository, manager, sink, false, 20);
        var row = new TranscriptEventOutbox(); row.setId(id);
        when(repository.lockNextNotification()).thenReturn(Optional.of(row));
        
    }
    @Test void httpFailureChangesOnlyNotificationState() {
        doThrow(new IllegalStateException("offline")).when(sink).deliver(any());
        queue.runOne(); queue.runOne();
        verify(repository, times(2)).notificationFailed(eq(id), eq(20), any(), eq("IllegalStateException"));
        verify(repository, times(2)).lockNextNotification();
        verifyNoMoreInteractions(repository);
    }
    @Test void successfulRetryCompletesIndependentJob() {
        queue.runOne();
        verify(repository).lockNextNotification(); verify(repository).notificationDelivered(id);
        verifyNoMoreInteractions(repository);
    }
    @Test void noJobMakesNoHttpRequest() {
        when(repository.lockNextNotification()).thenReturn(Optional.empty());
        queue.runOne(); verify(sink, never()).deliver(any());
    }
    @Test void handoffOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> queue.enqueue(id)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }
}