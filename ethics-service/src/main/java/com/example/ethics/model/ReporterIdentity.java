package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * ES-212 — the reporter behind a CONFIDENTIAL or NAMED report, encrypted.
 *
 * <p>There is no getter for a name, an e-mail or a phone number on this class, because
 * there is no column holding one. The entity exposes ciphertext and the id of the key
 * that produced it; turning that back into a person is
 * {@link com.example.ethics.identity.ReporterIdentityService}'s job, and that is where the
 * authorisation check lives. Keeping the plaintext off the entity means no JPA projection,
 * no {@code toString()}, no debugger session and no accidental log line can leak it — the
 * class simply has nothing to leak.
 *
 * <p>Never written for ANONYMOUS reports. That is not enforced here but in the schema
 * (V20 {@code fk_reporter_identity_report_mode}), which binds this row's mode to the
 * report's own mode, so the illegal combination has no representation in the database.
 */
@Entity
@Table(name = "reporter_identities")
public class ReporterIdentity {

    @Id
    @Column(name = "case_id", nullable = false, updatable = false) private UUID caseId;
    @Column(nullable = false, updatable = false, length = 20) private String mode;
    @Column(name = "key_id", nullable = false, updatable = false, length = 120) private String keyId;
    @Column(nullable = false, updatable = false) private byte[] nonce;
    @Column(nullable = false, updatable = false) private byte[] ciphertext;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ReporterIdentity() {}

    public ReporterIdentity(UUID caseId, String mode, String keyId, byte[] nonce,
                            byte[] ciphertext, Instant createdAt) {
        this.caseId = caseId;
        this.mode = mode;
        this.keyId = keyId;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
        this.createdAt = createdAt;
    }

    public UUID getCaseId() { return caseId; }
    public String getMode() { return mode; }
    public String getKeyId() { return keyId; }
    public byte[] getNonce() { return nonce; }
    public byte[] getCiphertext() { return ciphertext; }
    public Instant getCreatedAt() { return createdAt; }
}
