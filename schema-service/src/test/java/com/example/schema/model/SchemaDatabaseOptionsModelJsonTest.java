package com.example.schema.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-8 (capability M15 — Codex 019e32bc, ADR-0020): wire-contract tests
 * for the authoritative {@link DatabaseOptionsInfo}. Unlike the seven per-table
 * inventories, {@code databaseOptions} is a nullable singleton on
 * {@link SchemaSnapshot}: this locks the loss-less round-trip, the additive
 * serialization of the set singleton, and the {@code null} builder default.
 */
class SchemaDatabaseOptionsModelJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static DatabaseOptionsInfo sample() {
        return new DatabaseOptionsInfo(
                "workcube", "SQL_Latin1_General_CP1_CI_AS", 150, "SIMPLE",
                true, "ON", "CHECKSUM",
                true, true, false, false, false,
                true, true, true, false, true, true, false, false,
                3, 1, 5_242_880L, 1_048_576L);
    }

    @Test
    void databaseOptionsInfo_roundTrip() throws Exception {
        DatabaseOptionsInfo db = sample();

        DatabaseOptionsInfo back =
                mapper.readValue(mapper.writeValueAsString(db), DatabaseOptionsInfo.class);

        assertThat(back).isEqualTo(db);
        assertThat(back.compatibilityLevel()).isEqualTo(150);
        assertThat(back.recoveryModel()).isEqualTo("SIMPLE");
        assertThat(back.readCommittedSnapshotEnabled()).isTrue();
        assertThat(back.logFileSizeKb()).isEqualTo(1_048_576L);
    }

    @Test
    void schemaSnapshot_serializesDatabaseOptionsAsSingleton() throws Exception {
        SchemaSnapshot snap = SchemaSnapshot.builder()
                .version("1.1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0))
                .databaseOptions(sample())
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();

        String json = mapper.writeValueAsString(snap);
        assertThat(json).contains("\"databaseOptions\"");

        SchemaSnapshot back = mapper.readValue(json, SchemaSnapshot.class);
        assertThat(back.databaseOptions()).isNotNull();
        assertThat(back.databaseOptions().databaseName()).isEqualTo("workcube");
        assertThat(back.databaseOptions().dataFileCount()).isEqualTo(3);
    }

    @Test
    void schemaSnapshot_builderDefault_databaseOptionsNull() {
        SchemaSnapshot snap = SchemaSnapshot.builder()
                .version("1.1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0))
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();

        assertThat(snap.databaseOptions()).isNull();
    }
}
