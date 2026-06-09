package com.example.endpointadmin.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link RolloutFailureState} as its LOWER-case wire value so the DB
 * column matches the contract domain + the {@code ck_erf_state} CHECK. Not
 * {@code autoApply} — applied explicitly on the mapped columns to avoid
 * surprising any future {@code RolloutFailureState} column that wants a
 * different representation.
 */
@Converter
public class RolloutFailureStateConverter implements AttributeConverter<RolloutFailureState, String> {

    @Override
    public String convertToDatabaseColumn(RolloutFailureState attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public RolloutFailureState convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RolloutFailureState.fromWire(dbData);
    }
}
