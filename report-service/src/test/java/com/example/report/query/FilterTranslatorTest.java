package com.example.report.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FilterTranslator — AG Grid filter model to SQL WHERE translation.
 * SK-7 coverage target.
 */
class FilterTranslatorTest {

    private FilterTranslator translator;
    private final Set<String> allowed = Set.of("name", "amount", "created_at", "status");

    @BeforeEach
    void setUp() {
        translator = new FilterTranslator();
    }

    @Test
    void translate_null_returnsEmptyClause() {
        var result = translator.translate(null, allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_empty_returnsEmptyClause() {
        var result = translator.translate(Map.of(), allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_disallowedColumn_skipped() {
        var result = translator.translate(
                Map.of("secret_col", Map.of("type", "equals", "filter", "x")),
                allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_setFilter_producesInClause() {
        var result = translator.translate(
                Map.of("status", Map.of("filterType", "set", "values", List.of("ACTIVE", "PENDING"))),
                allowed);
        assertTrue(result.whereClause().contains("[status] IN"));
    }

    @Test
    void translate_setFilter_emptyValues_returnsEmpty() {
        var result = translator.translate(
                Map.of("status", Map.of("filterType", "set", "values", List.of())),
                allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_contains_producesLike() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "contains", "filter", "test")),
                allowed);
        assertTrue(result.whereClause().contains("[name] LIKE"));
        assertTrue(result.params().hasValue("p1"));
    }

    @Test
    void translate_equals_producesEquals() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "equals", "filter", "exact")),
                allowed);
        assertTrue(result.whereClause().contains("[name] = :"));
    }

    @Test
    void translate_notEqual_producesNotEqual() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "notEqual", "filter", "x")),
                allowed);
        assertTrue(result.whereClause().contains("[name] <> :"));
    }

    @Test
    void translate_greaterThan() {
        var result = translator.translate(
                Map.of("amount", Map.of("type", "greaterThan", "filter", 100)),
                allowed);
        assertTrue(result.whereClause().contains("[amount] > :"));
    }

    @Test
    void translate_lessThanOrEqual() {
        var result = translator.translate(
                Map.of("amount", Map.of("type", "lessThanOrEqual", "filter", 50)),
                allowed);
        assertTrue(result.whereClause().contains("[amount] <= :"));
    }

    @Test
    void translate_inRange_producesBetween() {
        var result = translator.translate(
                Map.of("amount", Map.of("type", "inRange", "filter", 10, "filterTo", 100)),
                allowed);
        assertTrue(result.whereClause().contains("BETWEEN"));
    }

    @Test
    void translate_blank_producesIsNull() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "blank")),
                allowed);
        assertTrue(result.whereClause().contains("IS NULL"));
    }

    @Test
    void translate_notBlank_producesIsNotNull() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "notBlank")),
                allowed);
        assertTrue(result.whereClause().contains("IS NOT NULL"));
    }

    @Test
    void translate_multipleFilters_joinsWithAnd() {
        var result = translator.translate(Map.of(
                "name", Map.of("type", "contains", "filter", "a"),
                "amount", Map.of("type", "greaterThan", "filter", 10)
        ), allowed);
        assertTrue(result.whereClause().contains("AND"));
    }

    @Test
    void translate_unknownType_skipped() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "unknownOp", "filter", "x")),
                allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_startsWith() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "startsWith", "filter", "A")),
                allowed);
        assertTrue(result.whereClause().contains("[name] LIKE"));
    }

    @Test
    void translate_endsWith() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "endsWith", "filter", "Z")),
                allowed);
        assertTrue(result.whereClause().contains("[name] LIKE"));
    }
}
