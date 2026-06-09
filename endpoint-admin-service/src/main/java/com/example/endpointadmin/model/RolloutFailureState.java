package com.example.endpointadmin.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** #527 failed-device rollout queue — persisted lowercase to match the contract
 *  JSON schema + the V60 CHECK domains (via the nested AttributeConverter). */
public enum RolloutFailureState {
    NEW("new"), RETRYING("retrying"), QUARANTINED("quarantined"), ESCALATED("escalated"), RESOLVED("resolved"), WAIVED("waived");

    private final String wire;
    RolloutFailureState(String wire) { this.wire = wire; }
    public String wire() { return wire; }
    public boolean isActive() { return this == NEW || this == RETRYING || this == QUARANTINED || this == ESCALATED; }

    public static RolloutFailureState fromWire(String w) {
        for (RolloutFailureState v : values()) { if (v.wire.equals(w)) { return v; } }
        throw new IllegalArgumentException("unknown RolloutFailureState: " + w);
    }

    @Converter
    public static class Conv implements AttributeConverter<RolloutFailureState, String> {
        @Override public String convertToDatabaseColumn(RolloutFailureState v) { return v == null ? null : v.wire; }
        @Override public RolloutFailureState convertToEntityAttribute(String s) { return s == null ? null : fromWire(s); }
    }
}
