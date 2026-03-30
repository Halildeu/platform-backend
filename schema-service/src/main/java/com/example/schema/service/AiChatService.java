package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI-powered schema chat — answers natural language questions about the database.
 * Supports Text2SQL (query generation) and schema Q&A.
 *
 * Uses external LLM API (Claude/OpenAI) with RAG-style schema context injection.
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    @Value("${schema.ai.api-key:}")
    private String apiKey;

    @Value("${schema.ai.api-url:https://api.anthropic.com/v1/messages}")
    private String apiUrl;

    @Value("${schema.ai.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${schema.ai.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate = new RestTemplate();

    public record ChatRequest(String message, String context) {}
    public record ChatResponse(String answer, String sql, List<String> referencedTables, boolean aiGenerated) {}

    /**
     * Process a natural language question about the schema.
     */
    public ChatResponse chat(String userMessage, SchemaSnapshot snapshot) {
        // 1. Try to answer with local logic first (no LLM needed)
        ChatResponse localAnswer = tryLocalAnswer(userMessage, snapshot);
        if (localAnswer != null) return localAnswer;

        // 2. If LLM is not configured, return helpful fallback
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return new ChatResponse(
                "AI chat is not configured. Set schema.ai.api-key and schema.ai.enabled=true to enable.\n\n" +
                "Meanwhile, try these tools:\n" +
                "- Column Search: finds columns across all tables\n" +
                "- Find Path: discovers join paths between tables\n" +
                "- Impact Analysis: shows affected tables",
                null, List.of(), false
            );
        }

        // 3. Build schema context for LLM
        String schemaContext = buildSchemaContext(snapshot, userMessage);

        // 4. Call LLM API
        return callLlm(userMessage, schemaContext, snapshot);
    }

    /**
     * Try to answer common questions without LLM.
     */
    private ChatResponse tryLocalAnswer(String message, SchemaSnapshot snapshot) {
        String lower = message.toLowerCase().trim();

        // "COLUMN_NAME hangi tablolarda var?"
        if (lower.contains("hangi tablo") || lower.contains("which table") || lower.contains("nerede")) {
            // Extract potential column name (uppercase word ending in _ID or _CODE)
            String[] words = message.toUpperCase().split("[\\s,?.!]+");
            for (String word : words) {
                if ((word.endsWith("_ID") || word.endsWith("_CODE") || word.endsWith("_NAME")) && word.length() > 3) {
                    return searchColumn(word, snapshot);
                }
            }
        }

        // "kaç tablo var?" / "how many tables?"
        if (lower.contains("kaç tablo") || lower.contains("how many table")) {
            return new ChatResponse(
                String.format("Veritabanında toplam **%d tablo** ve **%d kolon** bulunuyor.\n\n" +
                    "- %d ilişki keşfedildi\n- %d domain tespit edildi\n- En büyük hub: %s",
                    snapshot.metadata().tableCount(), snapshot.metadata().columnCount(),
                    snapshot.metadata().relationshipCount(), snapshot.metadata().domainCount(),
                    snapshot.analysis().hubTables().isEmpty() ? "N/A" : snapshot.analysis().hubTables().getFirst().table()),
                null, List.of(), false
            );
        }

        // "TABLENAME tablosu hakkında bilgi"
        for (String tableName : snapshot.tables().keySet()) {
            if (lower.contains(tableName.toLowerCase())) {
                return describeTable(tableName, snapshot);
            }
        }

        return null; // Fallback to LLM
    }

    private ChatResponse searchColumn(String columnName, SchemaSnapshot snapshot) {
        List<String> tables = new ArrayList<>();
        for (var entry : snapshot.tables().entrySet()) {
            for (ColumnInfo col : entry.getValue().columns()) {
                if (col.name().equalsIgnoreCase(columnName)) {
                    tables.add(entry.getKey());
                }
            }
        }

        if (tables.isEmpty()) {
            return new ChatResponse(
                String.format("**%s** kolonu hiçbir tabloda bulunamadı.", columnName),
                null, List.of(), false
            );
        }

        return new ChatResponse(
            String.format("**%s** kolonu **%d tabloda** bulunuyor:\n\n%s",
                columnName, tables.size(),
                tables.stream().limit(30).map(t -> "- " + t).collect(Collectors.joining("\n")) +
                (tables.size() > 30 ? String.format("\n\n... ve %d tablo daha", tables.size() - 30) : "")),
            null, tables.subList(0, Math.min(tables.size(), 10)), false
        );
    }

    private ChatResponse describeTable(String tableName, SchemaSnapshot snapshot) {
        TableInfo table = snapshot.tables().get(tableName);
        if (table == null) return null;

        List<Relationship> outgoing = snapshot.relationships().stream()
            .filter(r -> r.fromTable().equals(tableName)).toList();
        List<Relationship> incoming = snapshot.relationships().stream()
            .filter(r -> r.toTable().equals(tableName)).toList();

        String pkCols = table.columns().stream().filter(ColumnInfo::pk).map(ColumnInfo::name).collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("## %s\n\n", tableName));
        sb.append(String.format("- **%d kolon**, PK: %s\n", table.columns().size(), pkCols.isEmpty() ? "yok" : pkCols));
        sb.append(String.format("- **%d FK ilişkisi** (dışa), **%d referans** (içe)\n\n", outgoing.size(), incoming.size()));

        sb.append("### Kolonlar (ilk 20)\n");
        table.columns().stream().limit(20).forEach(c ->
            sb.append(String.format("- `%s` %s %s%s\n", c.name(), c.dataType(),
                c.pk() ? "**PK**" : "", c.nullable() ? " (nullable)" : ""))
        );

        if (!outgoing.isEmpty()) {
            sb.append("\n### FK İlişkileri\n");
            outgoing.forEach(r ->
                sb.append(String.format("- `%s` → **%s** (güven: %d%%)\n",
                    r.fromColumn(), r.toTable(), Math.round(r.confidence() * 100)))
            );
        }

        return new ChatResponse(sb.toString(), null,
            outgoing.stream().map(Relationship::toTable).limit(5).toList(), false);
    }

    private String buildSchemaContext(SchemaSnapshot snapshot, String question) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("Database: ").append(snapshot.metadata().database()).append("\n");
        ctx.append("Tables: ").append(snapshot.metadata().tableCount()).append("\n");
        ctx.append("Schema: ").append(snapshot.metadata().schema()).append("\n\n");

        // Include hub tables
        ctx.append("Hub tables (most referenced):\n");
        snapshot.analysis().hubTables().stream().limit(10).forEach(h ->
            ctx.append(String.format("  %s (%d refs)\n", h.table(), h.incomingRefs()))
        );

        // Include domains
        ctx.append("\nDomains:\n");
        snapshot.domains().forEach((domain, tables) ->
            ctx.append(String.format("  %s: %d tables (%s...)\n", domain, tables.size(),
                tables.stream().limit(5).collect(Collectors.joining(", "))))
        );

        // Include relevant tables (mentioned in question)
        String upper = question.toUpperCase();
        for (var entry : snapshot.tables().entrySet()) {
            if (upper.contains(entry.getKey())) {
                ctx.append(String.format("\nTable %s columns: %s\n", entry.getKey(),
                    entry.getValue().columns().stream()
                        .map(c -> c.name() + " " + c.dataType())
                        .collect(Collectors.joining(", "))));
            }
        }

        return ctx.toString();
    }

    private ChatResponse callLlm(String userMessage, String schemaContext, SchemaSnapshot snapshot) {
        try {
            Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(
                    Map.of("role", "user", "content",
                        "You are a database expert assistant. Given the following database schema context, " +
                        "answer the user's question. If the question asks for data, generate SQL (SQL Server dialect). " +
                        "Always reference actual table and column names from the schema.\n\n" +
                        "Schema context:\n" + schemaContext + "\n\nQuestion: " + userMessage)
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) responseBody.get("content");
                if (content != null && !content.isEmpty()) {
                    String text = (String) content.getFirst().get("text");

                    // Extract SQL from response
                    String sql = null;
                    if (text.contains("```sql")) {
                        int start = text.indexOf("```sql") + 6;
                        int end = text.indexOf("```", start);
                        if (end > start) sql = text.substring(start, end).trim();
                    }

                    // Extract referenced tables
                    List<String> refs = snapshot.tables().keySet().stream()
                        .filter(t -> text.toUpperCase().contains(t))
                        .limit(10)
                        .toList();

                    return new ChatResponse(text, sql, refs, true);
                }
            }

            return new ChatResponse("LLM response was empty", null, List.of(), true);
        } catch (Exception e) {
            log.error("LLM API call failed: {}", e.getMessage());
            return new ChatResponse(
                "AI API call failed: " + e.getMessage() + "\n\nLocal answers are still available.",
                null, List.of(), false
            );
        }
    }
}
