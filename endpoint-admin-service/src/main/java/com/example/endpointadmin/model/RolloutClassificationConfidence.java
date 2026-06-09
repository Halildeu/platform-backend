package com.example.endpointadmin.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** #527 failed-device rollout queue — persisted lowercase to match the contract
 *  JSON schema + the V60 CHECK domains (via the nested AttributeConverter). */
public enum RolloutClassificationConfidence {
    HIGH("high"), MEDIUM("medium"), LOW("low");

    private final String wire;
    RolloutClassificationConfidence(String wire) { this.wire = wire; }
    public String wire() { return wire; }

    public static RolloutClassificationConfidence fromWire(String w) {
        for (RolloutClassificationConfidence v : values()) { if (v.wire.equals(w)) { return v; } }
        throw new IllegalArgumentException("unknown RolloutClassificationConfidence: " + w);
    }

    @Converter
    public static class Conv implements AttributeConverter<RolloutClassificationConfidence, String> {
        @Override public String convertToDatabaseColumn(RolloutClassificationConfidence v) { return v == null ? null : v.wire; }
        @Override public RolloutClassificationConfidence convertToEntityAttribute(String s) { return s == null ? null : fromWire(s); }
    }
}
