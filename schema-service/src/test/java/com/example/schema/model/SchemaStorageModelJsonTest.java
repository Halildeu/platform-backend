package com.example.schema.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-6 (capability M6 — Codex 019e329a, ADR-0020): wire-contract tests
 * for the authoritative {@link StorageInfo} inventory — loss-less round-trip
 * of the KB decomposition, and additive {@code storage} serialization on
 * {@link SchemaSnapshot} ({@link SchemaSnapshot.Builder} defaults yield an
 * empty list).
 */
class SchemaStorageModelJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void storageInfo_roundTripPreservesKbDecomposition() throws Exception {
        StorageInfo s = new StorageInfo(
                "INVOICE", "dbo", 12_500L, 4_096L, 3_800L, 2_400L, 1_000L, 300L, 100L);

        String json = mapper.writeValueAsString(s);
        StorageInfo back = mapper.readValue(json, StorageInfo.class);

        assertThat(back).isEqualTo(s);
        assertThat(back.reservedKb()).isEqualTo(4_096L);
        assertThat(back.lobKb()).isEqualTo(300L);
        assertThat(back.rowOverflowKb()).isEqualTo(100L);
    }

    @Test
    void schemaSnapshot_serializesStorageInventoryAdditively() throws Exception {
        StorageInfo s = new StorageInfo(
                "ORDERS", "dbo", 9_000L, 2_048L, 1_900L, 1_500L, 300L, 80L, 20L);
        SchemaSnapshot snap = SchemaSnapshot.builder()
                .version("1.1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0))
                .storage(List.of(s))
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();

        String json = mapper.writeValueAsString(snap);
        assertThat(json).contains("\"storage\"");

        SchemaSnapshot back = mapper.readValue(json, SchemaSnapshot.class);
        assertThat(back.storage()).hasSize(1);
        assertThat(back.storage().get(0).table()).isEqualTo("ORDERS");
        assertThat(back.storage().get(0).usedKb()).isEqualTo(1_900L);
    }

    @Test
    void schemaSnapshot_builderDefaults_yieldEmptyStorage() {
        SchemaSnapshot snap = SchemaSnapshot.builder()
                .version("1.1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0))
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();

        assertThat(snap.storage()).isEmpty();
    }
}
