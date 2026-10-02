package com.serban.notify.push;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Completes only after the entire bounded response, so the caller's deadline covers the body. */
final class BoundedPushResponse implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int received;
    private boolean failed;
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
    public void onNext(List<ByteBuffer> buffers) {
        if (failed) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > 16384 - received) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("Native push response exceeds limit"));
                return;
            }
            received += buffer.remaining();
        }
        delegate.onNext(buffers);
    }
    public void onError(Throwable error) { if (!failed) delegate.onError(error); }
    public void onComplete() { if (!failed) delegate.onComplete(); }
}
