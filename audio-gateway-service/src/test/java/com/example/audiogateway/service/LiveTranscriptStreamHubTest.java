package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.audiogateway.dto.LiveTranscriptEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

class LiveTranscriptStreamHubTest {

    private static LiveTranscriptEvent result(final String text) {
        return new LiveTranscriptEvent(
                text, "tr", 0.99, 1.0, 100.0, "m", "int8", "cpu", null,
                LiveTranscriptEvent.STATUS_DRAFT, null, java.util.List.of());
    }

    @Test
    void publishToUnsubscribedMeetingIsCheapNoOp() {
        final LiveTranscriptStreamHub hub = new LiveTranscriptStreamHub();
        assertThatCode(() -> hub.publish("m-nobody", result("x"))).doesNotThrowAnyException();
        assertThat(hub.activeMeetings()).isZero();
    }

    @Test
    void subscribersReceiveEventsAfterConnect() throws Exception {
        final LiveTranscriptStreamHub hub = new LiveTranscriptStreamHub();
        final List<String> received = new CopyOnWriteArrayList<>();
        final Disposable sub =
                hub.subscribe("m-1")
                        .subscribe(r -> received.add(r.text()));
        try {
            hub.publish("m-1", result("merhaba"));
            hub.publish("m-1", result("dunya"));
            // Small settle since Sinks.many multicast delivers synchronously on the
            // publisher thread but the assertion window is tolerated.
            waitFor(() -> received.size() == 2, Duration.ofSeconds(1));
            assertThat(received).containsExactly("merhaba", "dunya");
        } finally {
            sub.dispose();
        }
    }

    @Test
    void lateSubscriberDoesNotSeePastEvents() throws Exception {
        final LiveTranscriptStreamHub hub = new LiveTranscriptStreamHub();
        hub.publish("m-2", result("early")); // Nobody subscribed → dropped
        final List<String> received = new CopyOnWriteArrayList<>();
        final Disposable sub =
                hub.subscribe("m-2")
                        .subscribe(r -> received.add(r.text()));
        try {
            hub.publish("m-2", result("late"));
            waitFor(() -> received.size() == 1, Duration.ofSeconds(1));
            assertThat(received).containsExactly("late");
        } finally {
            sub.dispose();
        }
    }

    @Test
    void nullOrBlankMeetingIdIsNoOp() {
        final LiveTranscriptStreamHub hub = new LiveTranscriptStreamHub();
        assertThatCode(() -> hub.publish(null, result("x"))).doesNotThrowAnyException();
        assertThatCode(() -> hub.publish("", result("x"))).doesNotThrowAnyException();
        assertThatCode(() -> hub.publish(" ", result("x"))).doesNotThrowAnyException();
        assertThat(hub.activeMeetings()).isZero();
    }

    @Test
    void nullResultIsNoOp() {
        final LiveTranscriptStreamHub hub = new LiveTranscriptStreamHub();
        assertThatCode(() -> hub.publish("m-null", null)).doesNotThrowAnyException();
    }

    private static void waitFor(final BoolSupplier cond, final Duration timeout)
            throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.get()) return;
            Thread.sleep(10L);
        }
    }

    @FunctionalInterface
    private interface BoolSupplier {
        boolean get();
    }
}
