package com.example.endpointadmin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle state of a rollout failed-device queue item (Faz 22.5 #527,
 * contract §4). The wire + DB value is LOWER-case ({@code new}, {@code retrying}
 * …), so {@code @Enumerated(EnumType.STRING)} (which would persist the Java name
 * {@code NEW}) is WRONG — persistence goes through {@link RolloutFailureStateConverter}
 * and JSON through {@link JsonValue}/{@link JsonCreator} on {@link #wire()}.
 *
 * <p>Slice-1a creates only {@link #NEW}; the transition machine (§4) lands in a
 * follow-up slice (1b). The enum carries every state so the converter + CHECK
 * domain match the contract today.
 */
public enum RolloutFailureState {
    NEW("new"),
    RETRYING("retrying"),
    QUARANTINED("quarantined"),
    ESCALATED("escalated"),
    RESOLVED("resolved"),
    WAIVED("waived");

    /** The four states that hold the partial-unique active slot (contract §2). */
    public boolean isActive() {
        return this == NEW || this == RETRYING || this == QUARANTINED || this == ESCALATED;
    }

    private final String wire;

    RolloutFailureState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RolloutFailureState fromWire(String value) {
        for (RolloutFailureState s : values()) {
            if (s.wire.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown rollout failure state: " + value);
    }
}
