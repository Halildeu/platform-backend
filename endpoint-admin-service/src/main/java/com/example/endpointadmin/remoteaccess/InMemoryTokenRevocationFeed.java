package com.example.endpointadmin.remoteaccess;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory reference {@link TokenRevocationFeed} — DEV/TEST (single-process; prod = Redis pub/sub or a
 * durable queue). Synchronous local fanout: {@link #publish} delivers to every subscriber before it
 * returns, so a test can assert the heartbeat reacted. A subscriber that throws does NOT stop the others
 * (fanout reliability), but the failure surfaces so it can be metered (no silent drop → no fail-open).
 */
public final class InMemoryTokenRevocationFeed implements TokenRevocationFeed {

    private final CopyOnWriteArrayList<Consumer<RevocationEvent>> subscribers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(RevocationEvent event) {
        if (event == null) {
            return;
        }
        RuntimeException firstFailure = null;
        for (Consumer<RevocationEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex; // remember but keep fanning out to the rest
                }
            }
        }
        if (firstFailure != null) {
            // surface (don't swallow) so the runtime meters a fanout failure rather than failing open.
            throw firstFailure;
        }
    }

    @Override
    public void subscribe(Consumer<RevocationEvent> subscriber) {
        if (subscriber != null) {
            subscribers.add(subscriber);
        }
    }
}
