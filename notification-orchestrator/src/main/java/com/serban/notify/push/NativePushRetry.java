package com.serban.notify.push;

import com.serban.notify.adapter.ChannelAdapter.DeliveryAttemptResult;
import java.time.Duration;

public final class NativePushRetry {
    private NativePushRetry() {}
    public static Duration delay(Duration backoff, DeliveryAttemptResult result) {
        if (!"native_provider_retry".equals(result.failureReason())) return backoff;
        Object value = result.providerMetadata().get("retryAfterSeconds");
        long seconds = value instanceof Number number ? Math.min(604800, Math.max(0, number.longValue())) : 0;
        return Duration.ofSeconds(seconds).compareTo(backoff) > 0 ? Duration.ofSeconds(seconds) : backoff;
    }
}
