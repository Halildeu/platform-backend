package com.example.endpointadmin.migration;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-Postgres guard for V80 WORM/provenance constraints. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RemoteViewPolicyPublicationMigrationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000245");
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("endpoint_admin").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    @Autowired
    EntityManager entityManager;

    @Test
    void v80CreatesAllImmutableLedgersAndSingleUseConstraints() {
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                + "AND table_name IN ('remote_view_policy_approval_intakes','remote_view_policy_publications',"
                + "'remote_view_policy_revocations')"))
                .isEqualTo(3L);
        assertThat(count("SELECT count(*) FROM information_schema.table_constraints WHERE constraint_schema='public' "
                + "AND constraint_name IN ('uq_rv_policy_publication_approval',"
                + "'uq_rv_policy_publication_identity','uq_rv_policy_publication_digest',"
                + "'uq_rv_policy_intake_tenant_identity','uq_rv_policy_revocation_publication',"
                + "'uq_rv_policy_revocation_approval')"))
                .isEqualTo(6L);
        assertThat(count("SELECT count(*) FROM information_schema.triggers WHERE trigger_schema='public' "
                + "AND trigger_name IN ('trg_rv_policy_intake_worm','trg_rv_policy_intake_no_truncate',"
                + "'trg_rv_policy_publication_worm','trg_rv_policy_publication_no_truncate',"
                + "'trg_rv_policy_revocation_worm','trg_rv_policy_revocation_no_truncate')"))
                .isEqualTo(6L);
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname='public' "
                + "AND indexname IN ('ux_rv_policy_publication_genesis','ux_rv_policy_publication_successor')"))
                .isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' "
                + "AND data_type='character varying' AND ((table_name='remote_view_policy_approval_intakes' "
                + "AND column_name='policy_digest') OR (table_name='remote_view_policy_publications' "
                + "AND column_name IN ('policy_digest','baseline_digest','legal_evidence_digest',"
                + "'supersedes_policy_digest')) OR (table_name='remote_view_policy_revocations' "
                + "AND column_name='policy_digest'))"))
                .isEqualTo(6L);
        assertThat(count("SELECT count(*) FROM endpoint_admin_flyway_history WHERE version='80' AND success=true"))
                .isEqualTo(1L);
    }

    @Test
    void intakeLedgerRejectsMutation() {
        UUID approval = UUID.randomUUID();
        insertApproval(approval, "policy-a", "{}");
        insertIntake(approval, "policy-a", "1.0.0", digest('a'), "{\"x\":1}");

        assertThatThrownBy(() -> entityManager.createNativeQuery(
                        "UPDATE remote_view_policy_approval_intakes SET policy_version='2.0.0' "
                                + "WHERE approval_id=:approval")
                .setParameter("approval", approval).executeUpdate())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void partialUniqueIndexRejectsConcurrentGenesisForkShape() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertApproval(first, "policy-a", "{}");
        insertApproval(second, "policy-b", "{}");
        insertIntake(first, "policy-a", "1.0.0", digest('a'), "{\"x\":1}");
        insertIntake(second, "policy-b", "1.0.0", digest('b'), "{\"x\":1}");
        insertPublication(first, "policy-a", "1.0.0", digest('a'), null);

        assertThatThrownBy(() -> insertPublication(second, "policy-b", "1.0.0", digest('b'), null))
                .rootCause().hasMessageContaining("ux_rv_policy_publication_genesis");
    }

    @Test
    void partialUniqueIndexRejectsConcurrentSuccessorForkShape() {
        UUID genesis = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertApproval(genesis, "policy-chain", "{}");
        insertApproval(first, "policy-chain", "{}");
        insertApproval(second, "policy-chain", "{}");
        insertIntake(genesis, "policy-chain", "1.0.0", digest('a'), "{\"x\":1}");
        insertIntake(first, "policy-chain", "1.1.0", digest('b'), "{\"x\":1}");
        insertIntake(second, "policy-chain", "1.2.0", digest('c'), "{\"x\":1}");
        insertPublication(genesis, "policy-chain", "1.0.0", digest('a'), null);
        insertPublication(first, "policy-chain", "1.1.0", digest('b'), digest('a'));

        assertThatThrownBy(() -> insertPublication(second, "policy-chain", "1.2.0", digest('c'), digest('a')))
                .rootCause().hasMessageContaining("ux_rv_policy_publication_successor");
    }

    @Test
    void approvalJsonbRoundTripPreservesSemanticJcsDigest() throws Exception {
        String source = Files.readString(Path.of(getClass().getResource(
                "/remote-view-policy/example-policy.json").toURI()));
        RemoteViewJsonCanonicalizer canonicalizer = new RemoteViewJsonCanonicalizer();
        String expected = canonicalizer.digest(canonicalizer.strictParse(source));
        UUID approval = UUID.randomUUID();
        insertApproval(approval, "example-tr-domestic-view-only", source);

        String reloaded = (String) entityManager.createNativeQuery(
                        "SELECT CAST(after_state AS text) FROM policy_change_approvals WHERE id=:id")
                .setParameter("id", approval).getSingleResult();

        assertThat(canonicalizer.digest(canonicalizer.strictParse(reloaded))).isEqualTo(expected);
    }

    private long count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private void insertApproval(UUID id, String target, String afterJson) {
        entityManager.createNativeQuery("""
                        INSERT INTO policy_change_approvals
                            (id, tenant_id, title, target, proposer_subject, proposer_name, proposer_role,
                             reason, evidence_refs, change_kind, risk_tier, before_state, after_state, deadline,
                             status, current_approvers, created_at, updated_at, row_version)
                        VALUES
                            (:id, :tenant, 'title', :target, 'proposer', 'Proposer', 'privacy-owner',
                             'reason', CAST('[]' AS jsonb), 'CREATE', 'HIGH', NULL, CAST(:afterJson AS jsonb),
                             :deadline, 'APPROVED', CAST('[]' AS jsonb), :now, :now, 0)
                        """)
                .setParameter("id", id).setParameter("tenant", TENANT).setParameter("target", target)
                .setParameter("afterJson", afterJson).setParameter("deadline", NOW.plusSeconds(86_400))
                .setParameter("now", NOW).executeUpdate();
    }

    private void insertIntake(UUID approval, String policyId, String version, String policyDigest,
                              String canonicalSource) {
        entityManager.createNativeQuery("""
                        INSERT INTO remote_view_policy_approval_intakes
                            (approval_id, tenant_id, policy_id, policy_version, canonical_source, policy_digest,
                             created_by_subject, created_at)
                        VALUES (:approval, :tenant, :policyId, :version, :source, :digest, 'proposer', :now)
                        """)
                .setParameter("approval", approval).setParameter("tenant", TENANT)
                .setParameter("policyId", policyId).setParameter("version", version)
                .setParameter("source", canonicalSource).setParameter("digest", policyDigest)
                .setParameter("now", NOW).executeUpdate();
    }

    private void insertPublication(UUID approval, String policyId, String version, String policyDigest,
                                   String supersedes) {
        entityManager.createNativeQuery("""
                        INSERT INTO remote_view_policy_publications
                            (id, approval_id, tenant_id, policy_id, policy_version, deployment_class,
                             canonical_source, policy_digest, baseline_digest, legal_evidence_digest,
                             legal_evidence_status, supersedes_policy_digest, valid_from, valid_until,
                             review_by, legal_review_by, published_by_subject, published_at)
                        VALUES
                            (:id, :approval, :tenant, :policyId, :version, 'bounded-test', '{"x":1}', :digest,
                             :baselineDigest, :legalDigest, 'tracked-pending', :supersedes, :validFrom,
                             :validUntil, :reviewBy, :legalReviewBy, 'publisher', :publishedAt)
                        """)
                .setParameter("id", UUID.randomUUID()).setParameter("approval", approval)
                .setParameter("tenant", TENANT).setParameter("policyId", policyId)
                .setParameter("version", version).setParameter("digest", policyDigest)
                .setParameter("baselineDigest", digest('d')).setParameter("legalDigest", digest('e'))
                .setParameter("supersedes", supersedes).setParameter("validFrom", NOW.minusSeconds(60))
                .setParameter("validUntil", NOW.plusSeconds(7_200)).setParameter("reviewBy", NOW.plusSeconds(3_600))
                .setParameter("legalReviewBy", NOW.plusSeconds(3_600)).setParameter("publishedAt", NOW)
                .executeUpdate();
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
