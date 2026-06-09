package com.example.endpointadmin.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** #527 failed-device rollout queue — persisted lowercase to match the contract
 *  JSON schema + the V60 CHECK domains (via the nested AttributeConverter). */
public enum RolloutFailureEventType {
    DETECTED("detected"), TRANSITION("transition"), RETRY("retry"), RECLASSIFIED("reclassified"), ESCALATED("escalated"), WAIVED("waived"), REOPENED("reopened"), RESOLVED("resolved");

    private final String wire;
    RolloutFailureEventType(String wire) { this.wire = wire; }
    public String wire() { return wire; }

    public static RolloutFailureEventType fromWire(String w) {
        for (RolloutFailureEventType v : values()) { if (v.wire.equals(w)) { return v; } }
        throw new IllegalArgumentException("unknown RolloutFailureEventType: " + w);
    }

    @Converter
    public static class Conv implements AttributeConverter<RolloutFailureEventType, String> {
        @Override public String convertToDatabaseColumn(RolloutFailureEventType v) { return v == null ? null : v.wire; }
        @Override public RolloutFailureEventType convertToEntityAttribute(String s) { return s == null ? null : fromWire(s); }
    }
}
