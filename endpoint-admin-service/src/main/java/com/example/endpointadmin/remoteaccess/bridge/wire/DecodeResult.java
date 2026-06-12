package com.example.endpointadmin.remoteaccess.bridge.wire;

import java.util.Objects;

/**
 * Faz 22.6 T-2a (Codex 019eb9fb) — the result of decoding an untrusted wire message into a T-1 domain record.
 * Explicit ok/reject instead of exceptions so the T-2b streaming layer can map a rejection to a fail-closed
 * outcome (metrics + audit + ErrorFrame) without exception control flow on the hot path.
 *
 * <p>A {@code Reject} reason is an internal diagnostic (safe to log/audit) — it never echoes back attacker
 * bytes, only WHICH rule failed.
 */
public sealed interface DecodeResult<T> permits DecodeResult.Ok, DecodeResult.Reject {

    record Ok<T>(T value) implements DecodeResult<T> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    record Reject<T>(String reason) implements DecodeResult<T> {
        public Reject {
            reason = reason == null || reason.isBlank() ? "unspecified" : reason;
        }
    }

    static <T> DecodeResult<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> DecodeResult<T> reject(String reason) {
        return new Reject<>(reason);
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    /** The decoded value; throws if this is a rejection (use after {@link #isOk()} or in tests). */
    default T orElseThrow() {
        if (this instanceof Ok<T> ok) {
            return ok.value();
        }
        throw new IllegalStateException("decode rejected: " + ((Reject<T>) this).reason());
    }

    /** The rejection reason, or {@code null} when ok. */
    default String rejectReason() {
        return this instanceof Reject<T> reject ? reject.reason() : null;
    }
}
