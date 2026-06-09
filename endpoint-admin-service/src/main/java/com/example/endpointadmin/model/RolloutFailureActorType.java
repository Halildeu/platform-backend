package com.example.endpointadmin.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** #527 failed-device rollout queue — persisted lowercase to match the contract
 *  JSON schema + the V60 CHECK domains (via the nested AttributeConverter). */
public enum RolloutFailureActorType {
    AUTO("auto"), OPERATOR("operator"), SYSTEM("system");

    private final String wire;
    RolloutFailureActorType(String wire) { this.wire = wire; }
    public String wire() { return wire; }

    public static RolloutFailureActorType fromWire(String w) {
        for (RolloutFailureActorType v : values()) { if (v.wire.equals(w)) { return v; } }
        throw new IllegalArgumentException("unknown RolloutFailureActorType: " + w);
    }

    @Converter
    public static class Conv implements AttributeConverter<RolloutFailureActorType, String> {
        @Override public String convertToDatabaseColumn(RolloutFailureActorType v) { return v == null ? null : v.wire; }
        @Override public RolloutFailureActorType convertToEntityAttribute(String s) { return s == null ? null : fromWire(s); }
    }
}
