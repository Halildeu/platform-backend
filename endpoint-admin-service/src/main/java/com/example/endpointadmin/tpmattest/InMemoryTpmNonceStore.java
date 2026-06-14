package com.example.endpointadmin.tpmattest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz 22.3B (ADR-0039) gate-4 — single-replica {@link TpmNonceStore}
 * implementation (in-memory, node-local). Correct only for a single replica or
 * with session affinity; a distributed backend replaces it for scale-out (see
 * the interface javadoc). Registered only if no other {@link TpmNonceStore} bean
 * is present, so a distributed impl can take over by simply being defined.
 */
@Component
@ConditionalOnMissingBean(name = "distributedTpmNonceStore")
public class InMemoryTpmNonceStore implements TpmNonceStore {

    private record Entry(String scope, byte[] nonce, byte[] serverSecret, Instant expiresAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryTpmNonceStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void issue(String nonceId, String scope, byte[] nonce, byte[] serverSecret, Instant expiresAt) {
        entries.put(nonceId, new Entry(scope, nonce.clone(), serverSecret.clone(), expiresAt));
    }

    @Override
    public Optional<Consumed> consume(String nonceId, String scope) {
        if (nonceId == null || scope == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        var holder = new Object() { Consumed result; };
        entries.compute(nonceId, (id, e) -> {
            if (e == null) {
                return null;                      // not found
            }
            if (!now.isBefore(e.expiresAt())) {
                return null;                      // expired → evict
            }
            if (!constantTimeEquals(scope, e.scope())) {
                return e;                         // scope mismatch → retain, do NOT burn
            }
            holder.result = new Consumed(e.nonce().clone(), e.serverSecret().clone());
            return null;                          // consumed → evict
        });
        return Optional.ofNullable(holder.result);
    }

    @Override
    public void evictExpired() {
        Instant now = clock.instant();
        entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
    }

    int size() {
        return entries.size();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
