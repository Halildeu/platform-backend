package com.example.endpointadmin.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link ClassificationConfidence} as its LOWER-case wire value so the
 * DB column matches the contract domain + the {@code ck_erf_confidence} CHECK.
 */
@Converter
public class ClassificationConfidenceConverter
        implements AttributeConverter<ClassificationConfidence, String> {

    @Override
    public String convertToDatabaseColumn(ClassificationConfidence attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public ClassificationConfidence convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ClassificationConfidence.fromWire(dbData);
    }
}
