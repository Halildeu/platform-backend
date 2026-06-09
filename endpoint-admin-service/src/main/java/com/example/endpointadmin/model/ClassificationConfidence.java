package com.example.endpointadmin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Classification confidence for a rollout failed-device queue item (Faz 22.5
 * #527, contract §7). Wire + DB value is LOWER-case ({@code high}/{@code medium}/
 * {@code low}); persistence via {@link ClassificationConfidenceConverter}, JSON
 * via {@link JsonValue}/{@link JsonCreator}. Slice-1a manual create always
 * records the operator-supplied confidence; auto-classification confidence
 * upgrades are a deferred slice (§9.2).
 */
public enum ClassificationConfidence {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    private final String wire;

    ClassificationConfidence(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ClassificationConfidence fromWire(String value) {
        for (ClassificationConfidence c : values()) {
            if (c.wire.equals(value)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown classification confidence: " + value);
    }
}
