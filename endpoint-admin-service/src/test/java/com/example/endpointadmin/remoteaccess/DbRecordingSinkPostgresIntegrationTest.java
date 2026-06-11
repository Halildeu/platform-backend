package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.RecordingSink.RecordingSinkException;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.Entry;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 C-storage — {@link DbRecordingSink} against a real PostgreSQL (Testcontainers + Flyway V65):
 * append + read-back the hash-chain, and the DB-level WORM (PK overwrite + trigger UPDATE/DELETE refusal).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DbRecordingSinkPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private static SessionRecordingChain chainOf(int n) {
        SessionRecordingChain chain = new SessionRecordingChain();
        for (int i = 0; i < n; i++) {
            chain.append(RecordKind.AGENT_OUTPUT, "content-" + i, 1000L + i);
        }
        return chain;
    }

    private SessionRecordingChain readBack(String chainId) {
        List<Entry> rows = jdbc.query(
                "SELECT seq, timestamp_millis, kind, content_hash FROM " + SCHEMA
                        + ".session_recording_entry WHERE chain_id = ? ORDER BY seq",
                (rs, i) -> new Entry(rs.getLong("seq"), rs.getLong("timestamp_millis"),
                        RecordKind.valueOf(rs.getString("kind")), rs.getString("content_hash"), null, null),
                chainId);
        SessionRecordingChain rebuilt = new SessionRecordingChain();
        for (Entry r : rows) {
            rebuilt.append(r.kind(), r.contentHash(), r.timestampMillis());
        }
        return rebuilt;
    }

    @Test
    void appendsAndTheReadBackChainVerifies() throws RecordingSinkException {
        DbRecordingSink sink = new DbRecordingSink("sess-append", jdbc, SCHEMA);
        for (Entry e : chainOf(3).entries()) {
            sink.append(e);
        }
        assertTrue(sink.isWritable());
        assertTrue(readBack("sess-append").verifyIntegrity());
        assertEquals(3, readBack("sess-append").size());
    }

    @Test
    void duplicateSeqIsRejected() throws RecordingSinkException {
        DbRecordingSink sink = new DbRecordingSink("sess-dup", jdbc, SCHEMA);
        List<Entry> entries = chainOf(2).entries();
        sink.append(entries.get(0));
        // a second entry at the SAME (chain_id, seq=0) but a different hash → PK violation, fail-closed
        Entry collidingSeqZero = new Entry(0, 9999L, RecordKind.KILL,
                "other", entries.get(1).previousHash(), entries.get(1).entryHash());
        assertThrows(RecordingSinkException.class, () -> sink.append(collidingSeqZero));
    }

    @Test
    void theWormTriggerBlocksUpdate() throws RecordingSinkException {
        // a WORM-trigger refusal aborts the surrounding transaction, so each mutation is its own @DataJpaTest
        // test (one tx each) — the throw IS the WORM proof; no query follows the aborted statement.
        DbRecordingSink sink = new DbRecordingSink("sess-worm-u", jdbc, SCHEMA);
        sink.append(chainOf(1).entries().get(0));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE " + SCHEMA + ".session_recording_entry SET content_hash = 'tampered' WHERE chain_id = ?",
                "sess-worm-u"));
    }

    @Test
    void theWormTriggerBlocksDelete() throws RecordingSinkException {
        DbRecordingSink sink = new DbRecordingSink("sess-worm-d", jdbc, SCHEMA);
        sink.append(chainOf(1).entries().get(0));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM " + SCHEMA + ".session_recording_entry WHERE chain_id = ?", "sess-worm-d"));
    }

    @Test
    void aSessionRecorderBackedByTheDbSinkRecordsAndStaysHealthy() throws GeneralSecurityException {
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        var signer = new RecordingAnchorSigner("sess-rec", kpg.generateKeyPair().getPrivate(), "SHA256withECDSA");
        var recorder = new SessionRecorder(new DbRecordingSink("sess-rec", jdbc, SCHEMA), signer);
        assertTrue(recorder.record(RecordKind.SESSION_START, "h0", 1L));
        assertTrue(recorder.record(RecordKind.SESSION_END, "h1", 2L));
        assertTrue(recorder.isHealthy());
        assertEquals(2, readBack("sess-rec").size());
    }
}
