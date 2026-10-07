package com.example.auditconsumer.bot;

import static com.example.auditconsumer.bot.BotRecordingContract.*;
import static org.assertj.core.api.Assertions.*;
import com.example.auditconsumer.audit.AuditIntegrityVerifier;
import com.example.auditconsumer.repository.AuditEventRepository;
import com.example.auditconsumer.service.AuditEventPersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL: no disabledWithoutDocker, mocked locking, or H2 substitution. Runs in the existing audit CI lane. */
@Testcontainers
@Import(BotRecordingOwnerPostgresIntegrationTest.Time.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "audit.consumer.enabled=false", "audit.consent-events.outbox.poller.enabled=false", "eureka.client.enabled=false"})
class BotRecordingOwnerPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl() + "&currentSchema=audit_event");
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.flyway.default-schema", () -> "audit_event");
        r.add("spring.flyway.schemas", () -> "audit_event");
        r.add("spring.jpa.properties.hibernate.default_schema", () -> "audit_event");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    @Autowired BotRecordingOwner service;
    @Autowired AuditEventPersistenceService legacy;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditEventRepository audit;
    @Autowired AuditIntegrityVerifier verifier;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    static final AtomicLong COMPANY = new AtomicLong(50000);
    static final MutableClock TIME = new MutableClock();
    @TestConfiguration static class Time { @Bean @Primary Clock botTestClock() { return TIME; } }
    static class MutableClock extends Clock {
        volatile Instant value = Instant.parse("2026-10-07T10:00:00Z");
        @Override public ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(value, zone); }
        @Override public Instant instant() { return value; }
    }
    @BeforeEach void resetTime() { TIME.value = Instant.parse("2026-10-07T10:00:00Z"); }

    @Test void grantBindWithdrawAndExactRetriesAreDurableAndAudited() throws Exception {
        Grant command = grant();
        var first = service.grant(command);
        assertThat(service.grant(command)).isEqualTo(first); // Includes lost-response / after-commit retry.
        var bound = service.bind(bind(first));
        assertThat(bound.state()).isEqualTo("BOUND");
        assertThat(service.bind(bind(first))).isEqualTo(bound);
        var revoked = service.revoke(lookup(first));
        assertThat(revoked.state()).isEqualTo("REVOKED");
        assertThat(service.revoke(lookup(first))).isEqualTo(revoked);
        assertThat(service.grant(command)).isEqualTo(revoked);
        assertThatThrownBy(() -> service.bind(bind(first))).isInstanceOf(ResponseStatusException.class);
        assertThat(service.lookup(lookup(first))).isEqualTo(revoked);
        assertThat(verifier.verifyTenant(command.owner().companyId()).valid()).isTrue();
        var events = audit.findByTenantIdOrderBySeqAsc(command.owner().companyId());
        assertThat(events).hasSize(3);
        for (var event : events) {
            var row = jdbc.queryForMap("select snapshot_json, snapshot_hash from audit_event.bot_recording_evidence where id = ?", event.getId());
            String bytes = (String) row.get("snapshot_json");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.getBytes(StandardCharsets.UTF_8)));
            assertThat(row.get("snapshot_hash")).isEqualTo(hash).isEqualTo(event.getCorrelationId());
            assertThat(json.readValue(bytes, Snapshot.class).revision()).isEqualTo(event.getChunkSeq());
        }
    }

    @Test void changedIdempotentCommandAndChangedBindingDoNotReplaceEvidence() {
        var command = grant(); var first = service.grant(command);
        var changed = new Grant(command.requestKey(), UUID.randomUUID(), command.owner(), command.consentVersion(),
                command.consentTextHash(), command.locale(), command.expiresAt());
        assertThatThrownBy(() -> service.grant(changed)).isInstanceOf(ResponseStatusException.class);
        var bound = service.bind(bind(first));
        var replacement = new Bind(first.intentId(), command.owner(), command.meetingId(), 1,
                new Binding("teams-capture-worker", "different-call", "media-1"));
        assertThatThrownBy(() -> service.bind(replacement)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.lookup(lookup(first))).isEqualTo(bound);
    }

    @Test void crossTenantSubjectScopeAndMeetingCannotReadBindOrWithdraw() {
        var first = service.grant(grant()); var own = first.grant().owner();
        var strangers = java.util.List.of(
                new Owner(own.companyId() + 900000, own.userId(), own.issuer(), own.subject(), own.tenantId(), own.orgId(), own.authzPrincipal(), own.microsoftTenantId(), own.organizerId()),
                new Owner(own.companyId(), own.userId(), own.issuer(), "someone-else", own.tenantId(), own.orgId(), own.authzPrincipal(), own.microsoftTenantId(), own.organizerId()));
        for (var stranger : strangers) {
            var wrong = new Lookup(first.intentId(), OwnerKey.of(stranger), first.grant().meetingId());
            assertThatThrownBy(() -> service.lookup(wrong)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.revoke(wrong)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.bind(new Bind(first.intentId(), stranger, wrong.meetingId(), 1, bind(first).binding())))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.lookup(new Lookup(first.intentId(), OwnerKey.of(own), UUID.randomUUID()))).isInstanceOf(ResponseStatusException.class);
        assertThat(service.lookup(lookup(first))).isEqualTo(first);
    }

    @Test void concurrentGrantRetryProducesOneIntentAndOneAuditRow() throws Exception {
        var command = grant(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return service.grant(command); });
            var b = pool.submit(() -> { start.await(); return service.grant(command); });
            start.countDown(); assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(audit.findByTenantIdOrderBySeqAsc(command.owner().companyId())).hasSize(1);
    }

    @Test void concurrentBindAndWithdrawalAlwaysFinishTerminal() throws Exception {
        var first = service.grant(grant()); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var bind = pool.submit(() -> {
                start.await();
                try { return service.bind(bind(first)).state(); }
                catch (ResponseStatusException ex) { assertThat(ex.getStatusCode().value()).isEqualTo(409); return "REJECTED"; }
            });
            var revoke = pool.submit(() -> { start.await(); return service.revoke(lookup(first)); });
            start.countDown(); bind.get(20, TimeUnit.SECONDS);
            assertThat(revoke.get(20, TimeUnit.SECONDS).state()).isEqualTo("REVOKED");
        }
        assertThat(service.lookup(lookup(first)).state()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.bind(bind(first))).isInstanceOf(ResponseStatusException.class);
        assertThat(verifier.verifyTenant(first.grant().owner().companyId()).valid()).isTrue();
    }

    @Test void failedEvidenceInsertRollsBackStateAndAuditTogether() {
        var first = service.grant(grant());
        jdbc.execute("alter table audit_event.bot_recording_evidence add constraint test_reject_binding check (revision <> 2) not valid");
        try { assertThatThrownBy(() -> service.bind(bind(first))).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("alter table audit_event.bot_recording_evidence drop constraint test_reject_binding"); }
        assertThat(service.lookup(lookup(first))).isEqualTo(first);
        assertThat(audit.findByTenantIdOrderBySeqAsc(first.grant().owner().companyId())).hasSize(1);
        assertThat(service.bind(bind(first)).state()).isEqualTo("BOUND");
    }

    @Test void failedAuditInsertRollsBackTheNewIntent() {
        var command = grant();
        jdbc.execute("alter table audit_event.audit_event add constraint test_reject_bot_grant check (event_type <> 'BOT_RECORDING_GRANTED') not valid");
        try { assertThatThrownBy(() -> service.grant(command)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("alter table audit_event.audit_event drop constraint test_reject_bot_grant"); }
        assertThat(jdbc.queryForObject("select count(*) from audit_event.bot_recording_intent where request_key = ?", Long.class, command.requestKey())).isZero();
        assertThat(audit.findByTenantIdOrderBySeqAsc(command.owner().companyId())).isEmpty();
        assertThat(service.grant(command).state()).isEqualTo("GRANTED");
    }

    @Test void failedStateUpdateDoesNotWriteAuditAndTerminalStateCannotReopen() {
        var first = service.grant(grant());
        jdbc.execute("alter table audit_event.bot_recording_intent add constraint test_reject_bound check (state <> 'BOUND') not valid");
        try { assertThatThrownBy(() -> service.bind(bind(first))).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("alter table audit_event.bot_recording_intent drop constraint test_reject_bound"); }
        assertThat(audit.findByTenantIdOrderBySeqAsc(first.grant().owner().companyId())).hasSize(1);
        service.revoke(lookup(first));
        assertThatThrownBy(() -> jdbc.update("update audit_event.bot_recording_intent set state='GRANTED',revision=1,revoked_at=null where id=?", first.intentId()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("delete from audit_event.bot_recording_evidence where intent_id=?", first.intentId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update audit_event.bot_recording_intent set grant_json='{}' where id=?", first.intentId())).isInstanceOf(RuntimeException.class);
    }

    @Test void expiryPreventsAdmissionButDoesNotPreventWithdrawal() {
        var command = grant();
        command = new Grant(command.requestKey(), command.meetingId(), command.owner(), command.consentVersion(), command.consentTextHash(),
                command.locale(), TIME.instant().plusSeconds(2));
        var first = service.grant(command);
        TIME.value = TIME.instant().plusSeconds(3);
        assertThatThrownBy(() -> service.bind(bind(first))).isInstanceOf(ResponseStatusException.class);
        assertThat(service.revoke(lookup(first)).state()).isEqualTo("REVOKED");
    }

    @Test void callerTransactionRollbackRemovesBothAuthorityAndEvidence() {
        var command = grant();
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(s -> { service.grant(command); s.setRollbackOnly(); });
        assertThat(audit.findByTenantIdOrderBySeqAsc(command.owner().companyId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from audit_event.bot_recording_intent where request_key = ?", Long.class, command.requestKey())).isZero();
    }

    @Test void legacyOrUnknownCaptureIdsNeverBecomeBotIntents() {
        var command = grant(); UUID legacyCapture = UUID.randomUUID();
        var fields = legacyFields(command, legacyCapture);
        assertThat(legacy.persist(fields, "1-0").result()).isEqualTo(AuditEventPersistenceService.PersistResult.PERSISTED);
        assertThatThrownBy(() -> service.bind(new Bind(legacyCapture, command.owner(), command.meetingId(), 1,
                new Binding("teams-capture-worker", "call-1", "media-1")))).isInstanceOf(ResponseStatusException.class);
        var first = service.grant(command);
        assertThat(jdbc.queryForObject("select count(*) from audit_event.recording_consent_grant where capture_id=?", Long.class, first.intentId())).isZero();
        fields.put("eventType", "RECORDING_CONSENT_REVOKED"); fields.put("consentRevision", "2"); fields.put("reasonCode", "USER_WITHDREW");
        assertThat(legacy.persist(fields, "2-0").result()).isEqualTo(AuditEventPersistenceService.PersistResult.PERSISTED);
        assertThat(service.lookup(lookup(first))).isEqualTo(first);
        assertThatThrownBy(() -> service.bind(new Bind(legacyCapture, command.owner(), command.meetingId(), 1, bind(first).binding())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void sameUserCanWithdrawAfterMicrosoftLinkOrRecordingPrincipalChanged() {
        var first = service.grant(grant()); var own = first.grant().owner();
        var changed = new Owner(own.companyId(), own.userId(), own.issuer(), own.subject(), own.tenantId(), own.orgId(),
                "user:new-principal", UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> service.bind(new Bind(first.intentId(), changed, first.grant().meetingId(), 1, bind(first).binding())))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.revoke(new Lookup(first.intentId(), OwnerKey.of(changed), first.grant().meetingId())).state()).isEqualTo("REVOKED");
    }

    @Test void grantEvidenceFailureAlsoRollsBackNewIntentAndAudit() {
        var command = grant();
        jdbc.execute("alter table audit_event.bot_recording_evidence add constraint test_reject_grant_evidence check (revision <> 1) not valid");
        try { assertThatThrownBy(() -> service.grant(command)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("alter table audit_event.bot_recording_evidence drop constraint test_reject_grant_evidence"); }
        assertThat(audit.findByTenantIdOrderBySeqAsc(command.owner().companyId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from audit_event.bot_recording_intent where request_key=?", Long.class, command.requestKey())).isZero();
    }

    @Test void establishedBindingAndEvidenceCannotBeRewritten() {
        var first = service.grant(grant()); service.bind(bind(first));
        assertThatThrownBy(() -> jdbc.update("update audit_event.bot_recording_intent set binding_json='{}',state='REVOKED',revision=3,revoked_at=now() where id=?", first.intentId()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update audit_event.bot_recording_evidence set snapshot_json='{}' where intent_id=?", first.intentId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.lookup(lookup(first)).state()).isEqualTo("BOUND");
    }

    @Test void botOwnerDoesNotRelyOnPostgresSearchPathForTables() {
        var command = grant();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL search_path TO public");
            assertThat(service.grant(command).state()).isEqualTo("GRANTED");
        });
        assertThat(audit.findByTenantIdOrderBySeqAsc(command.owner().companyId())).hasSize(1);
    }

    private static java.util.Map<String, String> legacyFields(Grant command, UUID capture) {
        var f = new java.util.HashMap<String, String>();
        f.put("eventType", "RECORDING_CONSENT_GRANTED"); f.put("tenantId", Long.toString(command.owner().companyId()));
        f.put("userId", Long.toString(command.owner().userId())); f.put("subjectId", command.owner().subject());
        f.put("meetingId", command.meetingId().toString()); f.put("captureId", capture.toString());
        f.put("canonicalTenantId", command.owner().tenantId().toString()); f.put("orgId", command.owner().orgId().toString());
        f.put("consentVersion", "mobile-v1"); f.put("consentTextHash", "sha256:" + "a".repeat(64)); f.put("locale", "tr-TR");
        f.put("timestampMs", Long.toString(TIME.instant().toEpochMilli())); return f;
    }

    private static Grant grant() {
        return new Grant(UUID.randomUUID(), UUID.randomUUID(), new Owner(COMPANY.incrementAndGet(), 81, "https://identity.test/realms/company", "user-1",
                UUID.randomUUID(), UUID.randomUUID(), "user:user-1", UUID.randomUUID(), UUID.randomUUID()),
                "bot-v1", "a".repeat(64), "tr-TR", TIME.instant().plusSeconds(3600));
    }
    private static Lookup lookup(Snapshot first) { return new Lookup(first.intentId(), OwnerKey.of(first.grant().owner()), first.grant().meetingId()); }
    private static Bind bind(Snapshot first) { return new Bind(first.intentId(), first.grant().owner(), first.grant().meetingId(), 1,
            new Binding("teams-capture-worker", "call-1", "media-1")); }
}
