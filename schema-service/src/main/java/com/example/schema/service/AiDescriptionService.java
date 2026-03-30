package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI-powered auto-description generation for tables and columns.
 * Uses heuristic rules + optional LLM for high-quality descriptions.
 */
@Service
public class AiDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(AiDescriptionService.class);

    public record Description(String table, String column, String description, String source) {}

    /**
     * Generate descriptions for a table and all its columns using heuristics.
     * No LLM needed — uses naming patterns, relationships, and data types.
     */
    public List<Description> generateForTable(String tableName, SchemaSnapshot snapshot) {
        TableInfo table = snapshot.tables().get(tableName);
        if (table == null) return List.of();

        List<Description> results = new ArrayList<>();

        // Table-level description
        results.add(new Description(tableName, null, describeTable(tableName, table, snapshot), "heuristic"));

        // Column-level descriptions
        for (ColumnInfo col : table.columns()) {
            results.add(new Description(tableName, col.name(),
                describeColumn(col, tableName, snapshot), "heuristic"));
        }

        return results;
    }

    /**
     * Batch generate descriptions for all tables (top N by importance).
     */
    public List<Description> generateBatch(SchemaSnapshot snapshot, int limit) {
        // Prioritize hub tables
        List<String> priority = snapshot.analysis().hubTables().stream()
            .map(h -> h.table())
            .limit(limit)
            .toList();

        List<Description> all = new ArrayList<>();
        for (String table : priority) {
            all.addAll(generateForTable(table, snapshot));
        }
        log.info("Generated {} descriptions for {} tables", all.size(), priority.size());
        return all;
    }

    private String describeTable(String name, TableInfo table, SchemaSnapshot snapshot) {
        int colCount = table.columns().size();
        long fkOut = snapshot.relationships().stream().filter(r -> r.fromTable().equals(name)).count();
        long fkIn = snapshot.relationships().stream().filter(r -> r.toTable().equals(name)).count();

        StringBuilder desc = new StringBuilder();

        // Naming pattern analysis
        if (name.startsWith("SETUP_")) desc.append("Sistem ayar/lookup tablosu. ");
        else if (name.endsWith("_HISTORY")) desc.append("Geçmiş/tarihçe tablosu. ");
        else if (name.endsWith("_ROW") || name.endsWith("_ROWS")) desc.append("Satır detay tablosu (master-detail). ");
        else if (name.endsWith("_LOG")) desc.append("İşlem log tablosu. ");
        else if (name.contains("YEDEK") || name.contains("BACKUP")) desc.append("Yedek/arşiv tablosu. ");

        desc.append(String.format("%d kolon", colCount));
        if (fkOut > 0) desc.append(String.format(", %d FK ilişkisi", fkOut));
        if (fkIn > 0) desc.append(String.format(", %d tablo tarafından referans alınıyor", fkIn));
        desc.append(".");

        return desc.toString();
    }

    private String describeColumn(ColumnInfo col, String tableName, SchemaSnapshot snapshot) {
        StringBuilder desc = new StringBuilder();

        // PK
        if (col.pk()) {
            desc.append("Birincil anahtar (PK). ");
        }

        // FK relationship
        Optional<Relationship> fkRel = snapshot.relationships().stream()
            .filter(r -> r.fromTable().equals(tableName) && r.fromColumn().equals(col.name()))
            .findFirst();
        fkRel.ifPresent(rel ->
            desc.append(String.format("FK → %s tablosuna referans (güven: %d%%). ",
                rel.toTable(), Math.round(rel.confidence() * 100)))
        );

        // Identity
        if (col.identity()) desc.append("Otomatik artan (identity). ");

        // Naming patterns
        String name = col.name().toUpperCase();
        if (name.endsWith("_DATE") || name.contains("DATE")) desc.append("Tarih alanı. ");
        else if (name.endsWith("_AMOUNT") || name.equals("AMOUNT")) desc.append("Tutar/miktar alanı. ");
        else if (name.endsWith("_NAME") || name.equals("NAME")) desc.append("İsim/ad alanı. ");
        else if (name.endsWith("_CODE") || name.equals("CODE")) desc.append("Kod alanı. ");
        else if (name.endsWith("_TYPE") || name.contains("TYPE")) desc.append("Tür/tip sınıflandırma alanı. ");
        else if (name.endsWith("_STATUS") || name.equals("STATUS")) desc.append("Durum alanı. ");
        else if (name.startsWith("IS_") || name.startsWith("HAS_")) desc.append("Boolean flag. ");
        else if (name.endsWith("_PRICE") || name.equals("PRICE")) desc.append("Fiyat alanı. ");
        else if (name.endsWith("_DESCRIPTION") || name.equals("DETAIL")) desc.append("Açıklama/detay metin alanı. ");

        // Data type info
        desc.append(String.format("[%s", col.dataType()));
        if (col.maxLength() > 0 && !col.dataType().equals("int") && !col.dataType().equals("bigint")) {
            desc.append(String.format("(%d)", col.maxLength()));
        }
        desc.append(col.nullable() ? ", nullable]" : ", not null]");

        return desc.toString().trim();
    }
}
