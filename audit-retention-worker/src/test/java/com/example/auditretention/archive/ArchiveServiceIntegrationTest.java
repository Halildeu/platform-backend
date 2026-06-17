package com.example.auditretention.archive;

import com.example.auditretention.audit.AuditChainSupport;
import com.example.auditretention.audit.AuditEventRecord;
import com.example.auditretention.config.AuditRetentionProperties;
import com.example.auditretention.store.ArchiveStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — end-to-end archival proof on REAL
 * Postgres (append-only/require-hash triggers) + REAL MinIO (Object Lock
 * COMPLIANCE + versioning), per ADR-0042 §4. No mocks: H2/stub can exercise
 * neither the trigger-protected source nor S3 version/retention semantics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ArchiveServiceIntegrationTest {

    private static final DockerImageName MINIO_IMAGE =
            DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");
    private static final String MINIO_USER = "minioadmin";
    private static final String MINIO_PASS = "minioadmin";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("audit_event").withUsername("postgres").withPassword("postgres");

    @Container
    static GenericContainer<?> minio = new GenericContainer<>(MINIO_IMAGE)
            .withEnv("MINIO_ROOT_USER", MINIO_USER)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASS)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static final AtomicInteger BUCKET_SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("audit.retention.run-once", () -> "false"); // call runOnce() manually
        r.add("audit.retention.scheduler.enabled", () -> "false");
        r.add("audit.retention.hot-window-days", () -> "0"); // cutoff = now
        r.add("audit.retention.scan-batch-size", () -> "1000");
        r.add("audit.retention.s3.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        r.add("audit.retention.s3.access-key", () -> MINIO_USER);
        r.add("audit.retention.s3.secret-key", () -> MINIO_PASS);
        r.add("audit.retention.s3.bucket", () -> "audit-archive-init");
    }

    @Autowired ArchiveService archiveService;
    @Autowired ArchiveStateStore stateStore;
    @Autowired AuditRetentionProperties props;
    @Autowired JdbcTemplate jdbc;

    private S3Client root;
    private String bucket;
    private static boolean sourceSchemaReady = false;

    @BeforeEach
    void setUp() throws Exception {
        if (!sourceSchemaReady) {
            // One multi-statement script (the $$ trigger bodies must NOT be split
            // on ';'); PostgreSQL's simple-query protocol runs them in one go.
            String sql = new String(getClass().getResourceAsStream("/audit_event_source_schema.sql").readAllBytes());
            jdbc.execute(sql);
            sourceSchemaReady = true;
        }
        // Clean source + worker state; fresh per-test bucket (COMPLIANCE objects
        // cannot be deleted, so isolation is by a new bucket each test).
        jdbc.execute("TRUNCATE TABLE audit_event.audit_event RESTART IDENTITY");
        jdbc.execute("TRUNCATE TABLE audit_archive.audit_archive_ledger");
        jdbc.execute("TRUNCATE TABLE audit_archive.audit_archive_tenant_anchor");
        jdbc.update("UPDATE audit_archive.audit_archive_cursor SET last_archived_seq = 0 WHERE id = 1");

        root = S3Client.builder()
                .endpointOverride(URI.create(props.getS3().getEndpoint()))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(MINIO_USER, MINIO_PASS)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        bucket = "audit-archive-" + BUCKET_SEQ.incrementAndGet();
        props.getS3().setBucket(bucket);
        root.createBucket(CreateBucketRequest.builder().bucket(bucket).objectLockEnabledForBucket(true).build());
    }

    @Test
    void archivesEligibleSegment_roundTripsContent_andComplianceRetention() throws Exception {
        List<AuditEventRecord> chain = insertChain(7L, 3, hoursAgo(48));

        var result = archiveService.runOnce();

        assertThat(result.rowsArchived()).isEqualTo(3);
        assertThat(result.segmentsWritten()).isEqualTo(1);
        assertThat(stateStore.readCursor()).isEqualTo(3L);

        // Ledger row + S3 object exist; content round-trips to the 3 rows.
        var ledger = jdbc.queryForMap("SELECT * FROM audit_archive.audit_archive_ledger");
        String key = (String) ledger.get("object_key");
        byte[] gz = root.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        String ndjson = gunzip(gz);
        assertThat(ndjson.strip().split("\n")).hasSize(3);
        assertThat(ndjson).contains(chain.get(0).getEntryHash()).contains(chain.get(2).getEntryHash());

        // COMPLIANCE retention ~7 years.
        var head = root.headObject(b -> b.bucket(bucket).key(key));
        assertThat(head.objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);
        assertThat(head.objectLockRetainUntilDate()).isAfter(Instant.now().plus(2550, ChronoUnit.DAYS));

        // Tenant anchor advanced to the last entry hash.
        String anchor = jdbc.queryForObject(
                "SELECT last_entry_hash FROM audit_archive.audit_archive_tenant_anchor WHERE tenant_id = 7", String.class);
        assertThat(anchor).isEqualTo(chain.get(2).getEntryHash());
    }

    @Test
    void stopsAtFirstHotRow_noSkipPastIt() {
        insertChainAt(7L, hoursAgo(48), 2, false);          // 2 cold (seq 1,2)
        insertOne(7L, lastEntryHash(7L), hoursFromNow(48));  // hot (seq 3)
        insertOne(7L, null, hoursAgo(48));                   // eligible-by-time but BEHIND the hot row (seq 4)

        var result = archiveService.runOnce();

        assertThat(result.rowsArchived()).isEqualTo(2);   // only the contiguous cold prefix
        assertThat(stateStore.readCursor()).isEqualTo(2L); // stopped before the hot row; did NOT jump to seq 4
    }

    @Test
    void failsClosed_onTamperedEntryHash() {
        insertChainAt(7L, hoursAgo(48), 1, false);
        // Insert a row with a structurally valid but WRONG entry_hash (require_hash
        // trigger only checks non-null). Verify-before-archive must catch it.
        insertRaw(7L, lastEntryHash(7L), hoursAgo(47), "f".repeat(64));

        assertThatThrownBy(() -> archiveService.runOnce()).isInstanceOf(ChainBreakException.class);
        assertThat(stateStore.readCursor()).isEqualTo(0L);          // no advance
        assertThat(countObjects()).isZero();                        // nothing archived
    }

    @Test
    void failsClosed_onBrokenLinkage() {
        insertChainAt(7L, hoursAgo(48), 1, false);
        // prev_hash points nowhere; entry_hash self-consistent with that wrong prev.
        insertOne(7L, "0".repeat(64), hoursAgo(47));

        assertThatThrownBy(() -> archiveService.runOnce()).isInstanceOf(ChainBreakException.class);
        assertThat(stateStore.readCursor()).isEqualTo(0L);
    }

    @Test
    void idempotentReRun_skipsPut_keepsSameVersion() {
        insertChain(7L, 3, hoursAgo(48));
        archiveService.runOnce();
        String v1 = jdbc.queryForObject(
                "SELECT object_version_id FROM audit_archive.audit_archive_ledger", String.class);

        // Simulate a crash where the cursor lagged but ledger + object persisted.
        jdbc.update("UPDATE audit_archive.audit_archive_cursor SET last_archived_seq = 0 WHERE id = 1");
        var result = archiveService.runOnce();

        assertThat(result.rowsArchived()).isZero();        // idempotent skip, no re-put
        assertThat(stateStore.readCursor()).isEqualTo(3L); // cursor caught back up
        String v2 = jdbc.queryForObject(
                "SELECT object_version_id FROM audit_archive.audit_archive_ledger", String.class);
        assertThat(v2).isEqualTo(v1);                      // same version, no new object
        assertThat(countObjects()).isEqualTo(2);           // object + manifest only
    }

    @Test
    void failsClosed_whenLatestVersionMovedUnexpectedly() {
        insertChain(7L, 2, hoursAgo(48));
        archiveService.runOnce();
        String key = jdbc.queryForObject("SELECT object_key FROM audit_archive.audit_archive_ledger", String.class);

        // An out-of-band actor writes a NEW version at the same key (COMPLIANCE
        // does not forbid this) — latest now != recorded version.
        root.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                .objectLockMode(ObjectLockMode.COMPLIANCE)
                .objectLockRetainUntilDate(Instant.now().plus(2557, ChronoUnit.DAYS)).build(),
                RequestBody.fromBytes("tampered".getBytes()));

        jdbc.update("UPDATE audit_archive.audit_archive_cursor SET last_archived_seq = 0 WHERE id = 1");
        assertThatThrownBy(() -> archiveService.runOnce())
                .isInstanceOf(ArchiveAnomalyException.class)
                .hasMessageContaining("latest version moved");
        assertThat(stateStore.readCursor()).isZero();
    }

    @Test
    void failsClosed_whenObjectExistsButLedgerAbsent() {
        List<AuditEventRecord> chain = insertChain(7L, 1, hoursAgo(48));
        String key = String.format("segments/seq-%019d-%019d.ndjson.gz",
                chain.get(0).getSeq(), chain.get(0).getSeq());
        // Pre-existing object at the derived key with NO ledger row (crash mid-write / external).
        root.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                .objectLockMode(ObjectLockMode.COMPLIANCE)
                .objectLockRetainUntilDate(Instant.now().plus(2557, ChronoUnit.DAYS)).build(),
                RequestBody.fromBytes("orphan".getBytes()));

        assertThatThrownBy(() -> archiveService.runOnce())
                .isInstanceOf(ArchiveAnomalyException.class)
                .hasMessageContaining("ledger-absent");
        assertThat(stateStore.readCursor()).isZero();
    }

    @Test
    void failsClosed_whenRecordedVersionContentMismatches() {
        insertChain(7L, 2, hoursAgo(48));
        archiveService.runOnce();
        // Corrupt the ledger's recorded object_sha256 — the recorded version still
        // exists, but version-specific verification must reject the content mismatch.
        jdbc.update("UPDATE audit_archive.audit_archive_ledger SET object_sha256 = ?", "0".repeat(64));
        jdbc.update("UPDATE audit_archive.audit_archive_cursor SET last_archived_seq = 0 WHERE id = 1");

        assertThatThrownBy(() -> archiveService.runOnce())
                .isInstanceOf(ArchiveAnomalyException.class)
                .hasMessageContaining("checksum mismatch");
        assertThat(stateStore.readCursor()).isZero();
    }

    @Test
    void complianceObjectCannotBeDeleted() {
        insertChain(7L, 2, hoursAgo(48));
        archiveService.runOnce();
        var obj = jdbc.queryForMap("SELECT object_key, object_version_id FROM audit_archive.audit_archive_ledger");
        String key = (String) obj.get("object_key");
        String versionId = (String) obj.get("object_version_id");

        // Even root cannot delete a specific COMPLIANCE-locked version.
        assertThatThrownBy(() -> root.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket).key(key).versionId(versionId).build()))
                .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
    }

    @Test
    void multiTenantInterleavedSegment_archivesAndAnchorsBoth() throws Exception {
        // Interleave two independent tenant chains across the global seq order.
        AuditEventRecord a1 = insertOne(7L, null, hoursAgo(48));
        AuditEventRecord b1 = insertOne(9L, null, hoursAgo(47));
        AuditEventRecord a2 = insertOne(7L, a1.getEntryHash(), hoursAgo(46));
        AuditEventRecord b2 = insertOne(9L, b1.getEntryHash(), hoursAgo(45));

        var result = archiveService.runOnce();
        assertThat(result.rowsArchived()).isEqualTo(4);

        assertThat(jdbc.queryForObject(
                "SELECT last_entry_hash FROM audit_archive.audit_archive_tenant_anchor WHERE tenant_id = 7", String.class))
                .isEqualTo(a2.getEntryHash());
        assertThat(jdbc.queryForObject(
                "SELECT last_entry_hash FROM audit_archive.audit_archive_tenant_anchor WHERE tenant_id = 9", String.class))
                .isEqualTo(b2.getEntryHash());

        // tenant_anchors JSON snapshot carries both tenants (parse — JSONB text
        // formatting/spacing is engine-defined).
        String anchorsJson = jdbc.queryForObject(
                "SELECT tenant_anchors::text FROM audit_archive.audit_archive_ledger", String.class);
        var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(anchorsJson);
        List<Long> tenantIds = new ArrayList<>();
        arr.forEach(n -> tenantIds.add(n.get("tenant_id").asLong()));
        assertThat(tenantIds).containsExactlyInAnyOrder(7L, 9L);
    }

    // --- helpers ----------------------------------------------------------

    private List<AuditEventRecord> insertChain(long tenantId, int count, Instant baseTime) {
        return insertChainAt(tenantId, baseTime, count, false);
    }

    private List<AuditEventRecord> insertChainAt(long tenantId, Instant baseTime, int count, boolean unusedHot) {
        List<AuditEventRecord> out = new ArrayList<>();
        String prev = lastEntryHash(tenantId);
        for (int i = 0; i < count; i++) {
            AuditEventRecord r = insertOne(tenantId, prev, baseTime.plusSeconds(i));
            prev = r.getEntryHash();
            out.add(r);
        }
        return out;
    }

    private AuditEventRecord insertOne(long tenantId, String prevHash, Instant eventTs) {
        AuditEventRecord r = newRecord(tenantId, prevHash, eventTs);
        r.setEntryHash(AuditChainSupport.computeEntryHash(prevHash, r));
        return doInsert(r);
    }

    /** Insert a row with a caller-supplied (possibly wrong) entry_hash. */
    private AuditEventRecord insertRaw(long tenantId, String prevHash, Instant eventTs, String entryHash) {
        AuditEventRecord r = newRecord(tenantId, prevHash, eventTs);
        r.setEntryHash(entryHash);
        return doInsert(r);
    }

    private AuditEventRecord doInsert(AuditEventRecord r) {
        Long seq = jdbc.queryForObject(
                "INSERT INTO audit_event.audit_event (id, tenant_id, event_type, session_id, user_id, chunk_seq, "
                        + "http_status, rejection_code, retry_after_seconds, correlation_id, event_timestamp, "
                        + "dedup_key, stream_entry_id, prev_hash, entry_hash, entry_hash_alg, entry_hash_version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING seq",
                Long.class,
                r.getId(), r.getTenantId(), r.getEventType(), r.getSessionId(), r.getUserId(), r.getChunkSeq(),
                r.getHttpStatus(), r.getRejectionCode(), r.getRetryAfterSeconds(), r.getCorrelationId(),
                java.time.OffsetDateTime.ofInstant(r.getEventTimestamp(), java.time.ZoneOffset.UTC),
                r.getDedupKey(), r.getStreamEntryId(), r.getPrevHash(), r.getEntryHash(),
                r.getEntryHashAlg(), r.getEntryHashVersion());
        r.setSeq(seq);
        return r;
    }

    private AuditEventRecord newRecord(long tenantId, String prevHash, Instant eventTs) {
        AuditEventRecord r = new AuditEventRecord();
        r.setId(java.util.UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setEventType("CHUNK_ADMISSION_REJECTED");
        r.setSessionId("sess-" + tenantId + "-" + eventTs.toEpochMilli());
        r.setUserId(990000L + tenantId);
        r.setChunkSeq(0L);
        r.setHttpStatus(413);
        r.setRejectionCode("AUDIO_GATEWAY_OVERSIZE");
        r.setRetryAfterSeconds(null);
        r.setCorrelationId(java.util.UUID.randomUUID().toString());
        r.setEventTimestamp(AuditChainSupport.normalizeTimestamp(eventTs));
        r.setDedupKey("dk-" + java.util.UUID.randomUUID());
        r.setStreamEntryId("1700000000000-0");
        r.setPrevHash(prevHash);
        r.setEntryHashAlg(AuditChainSupport.HASH_ALGORITHM);
        r.setEntryHashVersion(AuditChainSupport.HASH_VERSION);
        return r;
    }

    private String lastEntryHash(long tenantId) {
        List<String> h = jdbc.query(
                "SELECT entry_hash FROM audit_event.audit_event WHERE tenant_id = ? ORDER BY seq DESC LIMIT 1",
                (rs, n) -> rs.getString(1), tenantId);
        return h.isEmpty() ? null : h.get(0);
    }

    private int countObjects() {
        try {
            return root.listObjectsV2(b -> b.bucket(bucket)).keyCount();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Instant hoursAgo(int h) {
        return Instant.now().minus(h, ChronoUnit.HOURS);
    }

    private static Instant hoursFromNow(int h) {
        return Instant.now().plus(h, ChronoUnit.HOURS);
    }

    private static String gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
