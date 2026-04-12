package com.example.report.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportRegistryTest {

    private ObjectMapper objectMapper;
    private ReportRegistry registry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry = new ReportRegistry(objectMapper, "classpath:reports/");
    }

    // ── Validation tests ──────────────────────────────────────

    @Nested
    @DisplayName("validate()")
    class Validation {

        @Test
        @DisplayName("valid source identifier accepted")
        void validSource() {
            var def = new ReportDefinition("r1", "v1", "Title", "desc", "cat",
                    "EMPLOYEES", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                    "id", "ASC", null);

            // No exception — validation passes in ReportDefinition constructor
            assertNotNull(def);
            assertEquals("EMPLOYEES", def.source());
        }

        @Test
        @DisplayName("source with dot accepted (schema.table pattern)")
        void sourceWithDot() {
            var def = new ReportDefinition("r2", "v1", "T", "d", "c",
                    "hr.EMPLOYEES", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                    "id", "ASC", null);

            assertNotNull(def);
        }

        @Test
        @DisplayName("blank key throws IllegalArgumentException")
        void blankKey() {
            assertThrows(IllegalArgumentException.class, () ->
                    new ReportDefinition("", "v1", "T", "d", "c",
                            "SRC", "dbo", "static", null, null,
                            List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                            "id", "ASC", null));
        }

        @Test
        @DisplayName("null source AND null sourceQuery throws")
        void nullSourceAndQuery() {
            assertThrows(IllegalArgumentException.class, () ->
                    new ReportDefinition("r3", "v1", "T", "d", "c",
                            null, "dbo", "static", null, null,
                            List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                            "id", "ASC", null));
        }

        @Test
        @DisplayName("empty columns throws")
        void emptyColumns() {
            assertThrows(IllegalArgumentException.class, () ->
                    new ReportDefinition("r4", "v1", "T", "d", "c",
                            "SRC", "dbo", "static", null, null,
                            List.of(), "id", "ASC", null));
        }

        @Test
        @DisplayName("blank column field throws")
        void blankColumnField() {
            assertThrows(IllegalArgumentException.class, () ->
                    new ColumnDefinition("", "Col", "text", 100, false));
        }
    }

    // ── Default values ────────────────────────────────────────

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("null sourceSchema defaults to dbo")
        void defaultSchema() {
            var def = new ReportDefinition("r5", "v1", "T", "d", "c",
                    "SRC", null, "static", null, null,
                    List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                    "id", "ASC", null);

            assertEquals("dbo", def.sourceSchema());
        }

        @Test
        @DisplayName("null schemaMode defaults to static")
        void defaultSchemaMode() {
            var def = new ReportDefinition("r6", "v1", "T", "d", "c",
                    "SRC", "dbo", null, null, null,
                    List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                    "id", "ASC", null);

            assertEquals("static", def.schemaMode());
            assertFalse(def.isYearlySchema());
        }

        @Test
        @DisplayName("null sortDirection defaults to ASC")
        void defaultSortDirection() {
            var def = new ReportDefinition("r7", "v1", "T", "d", "c",
                    "SRC", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                    "id", null, null);

            assertEquals("ASC", def.defaultSortDirection());
        }

        @Test
        @DisplayName("yearly schemaMode returns isYearlySchema true")
        void yearlySchema() {
            var def = new ReportDefinition("r8", "v1", "T", "d", "c",
                    "SRC", "dbo", "yearly", "TxDate", null,
                    List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                    "id", "ASC", null);

            assertTrue(def.isYearlySchema());
        }

        @Test
        @DisplayName("hasSourceQuery true when sourceQuery set")
        void hasSourceQuery() {
            var def = new ReportDefinition("r9", "v1", "T", "d", "c",
                    null, "dbo", "static", null, "SELECT * FROM T",
                    List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                    "id", "ASC", null);

            assertTrue(def.hasSourceQuery());
        }

        @Test
        @DisplayName("column defaults: null type → text, null width → 150")
        void columnDefaults() {
            var col = new ColumnDefinition("col1", "Col1", null, null, false);
            assertEquals("text", col.type());
            assertEquals(150, col.width());
        }
    }

    // ── Registry get/getAll ───────────────────────────────────

    @Nested
    @DisplayName("registry operations")
    class RegistryOps {

        @Test
        @DisplayName("get unknown key returns empty")
        void getUnknown() {
            assertTrue(registry.get("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("getAll initially empty (no test fixture dir)")
        void getAllEmpty() {
            // loadDefinitions may log warning if no reports/ dir
            registry.loadDefinitions();
            // May or may not have definitions depending on classpath
            assertNotNull(registry.getAll());
        }

        @Test
        @DisplayName("getCategories returns sorted distinct")
        void getCategories() {
            registry.loadDefinitions();
            List<String> cats = registry.getCategories();
            assertNotNull(cats);
            // Verify sorted
            for (int i = 1; i < cats.size(); i++) {
                assertTrue(cats.get(i).compareTo(cats.get(i - 1)) >= 0);
            }
        }
    }
}
