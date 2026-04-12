package com.example.report.export;

import com.example.report.query.SqlBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CsvStreamingExporter}.
 * Verifies CSV format: UTF-8 BOM, semicolon separator, quoting rules.
 */
@ExtendWith(MockitoExtension.class)
class CsvStreamingExporterTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String SEPARATOR = ";";

    private final List<String> columns = List.of("id", "name", "city");

    private SqlBuilder.BuiltQuery buildQuery() {
        return new SqlBuilder.BuiltQuery("SELECT id, name, city FROM test", new MapSqlParameterSource());
    }

    @Test
    void export_withDataRows_producesValidCsv() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            handler.processRow(mockResultSet(1, "Alice", "Istanbul"));
            handler.processRow(mockResultSet(2, "Bob", "Ankara"));
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        byte[] bytes = out.toByteArray();
        // Verify UTF-8 BOM
        assertTrue(bytes.length >= 3);
        assertEquals(UTF8_BOM[0], bytes[0]);
        assertEquals(UTF8_BOM[1], bytes[1]);
        assertEquals(UTF8_BOM[2], bytes[2]);

        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] lines = csv.split("\\r?\\n");

        // Header line
        assertEquals("id;name;city", lines[0]);
        // Data rows
        assertEquals("1;Alice;Istanbul", lines[1]);
        assertEquals("2;Bob;Ankara", lines[2]);
    }

    @Test
    void export_emptyResultSet_producesHeaderOnly() {
        doNothing().when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        assertEquals(1, lines.length, "Only header line expected");
        assertEquals("id;name;city", lines[0]);
    }

    @Test
    void export_nullValues_producesEmptyFields() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn(null);
            when(rs.getObject("city")).thenReturn(null);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        assertEquals("1;;", lines[1], "Null values should produce empty fields");
    }

    @Test
    void export_valueContainingSeparator_isQuoted() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn("Alice;Bob");
            when(rs.getObject("city")).thenReturn("Normal");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        assertEquals("1;\"Alice;Bob\";Normal", lines[1]);
    }

    @Test
    void export_valueContainingQuotes_areEscaped() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn("Say \"hello\"");
            when(rs.getObject("city")).thenReturn("OK");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        // Quotes inside value are doubled and whole field is wrapped in quotes
        assertEquals("1;\"Say \"\"hello\"\"\";OK", lines[1]);
    }

    @Test
    void export_valueContainingNewline_isQuoted() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id")).thenReturn(1);
            when(rs.getObject("name")).thenReturn("Line1\nLine2");
            when(rs.getObject("city")).thenReturn("Simple");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        // The value with newline should be quoted
        assertTrue(csv.contains("\"Line1\nLine2\""), "Newline-containing value should be quoted");
    }

    @Test
    void export_usesSemicolonSeparator() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            handler.processRow(mockResultSet(1, "A", "B"));
            return null;
        }).when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvStreamingExporter.export(jdbc, buildQuery(), columns, out);

        String csv = extractCsvContent(out);
        String[] lines = csv.split("\\r?\\n");

        // Verify semicolon is used, not comma
        assertTrue(lines[0].contains(";"), "Header should use semicolon separator");
        assertFalse(lines[0].contains(","), "Header should not use comma separator");
    }

    @Test
    void export_jdbcThrows_wrapsInRuntimeException() {
        doThrow(new RuntimeException("DB error"))
                .when(jdbc).query(any(String.class), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                CsvStreamingExporter.export(jdbc, buildQuery(), columns, out));
        assertTrue(ex.getMessage().contains("CSV export failed") || ex.getMessage().contains("DB error"));
    }

    private ResultSet mockResultSet(Object id, String name, String city) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getObject("name")).thenReturn(name);
        when(rs.getObject("city")).thenReturn(city);
        return rs;
    }

    private String extractCsvContent(ByteArrayOutputStream out) {
        byte[] bytes = out.toByteArray();
        // Skip UTF-8 BOM (3 bytes)
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }
}
