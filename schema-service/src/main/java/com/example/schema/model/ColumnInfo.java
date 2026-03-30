package com.example.schema.model;

public record ColumnInfo(
    String name,
    String dataType,
    int maxLength,
    boolean nullable,
    boolean identity,
    boolean pk,
    int ordinal
) {}
