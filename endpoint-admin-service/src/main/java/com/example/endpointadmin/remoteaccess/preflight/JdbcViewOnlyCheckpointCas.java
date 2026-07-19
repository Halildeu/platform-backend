package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed, response-loss-safe checkpoint authority.
 *
 * <p>The caller must finish strict schema, DSSE and GitHub OIDC verification
 * before invoking this adapter. This class then owns the atomic one-use lease
 * redemption, executor binding, ordered CAS chain, byte-identical transport
 * retry and terminal lease close. Signed envelope bytes are public evidence;
 * bearer or credential material is never accepted by this API.</p>
 */
public final class JdbcViewOnlyCheckpointCas {
    private static final int MAX_LEASE_ENVELOPE_BYTES = 1_048_576;
    private static final int MAX_CHECKPOINT_ENVELOPE_BYTES = 524_288;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final Clock clock;
    private final String leaseTable;
    private final String checkpointTable;

    public JdbcViewOnlyCheckpointCas(JdbcTemplate jdbc,
                                     PlatformTransactionManager transactionManager,
                                     RemoteViewJsonCanonicalizer canonicalizer,
                                     Clock clock,
                                     String schema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + schema);
        }
        this.leaseTable = schema + ".view_only_checkpoint_leases";
        this.checkpointTable = schema + ".view_only_external_checkpoints";
    }

    /** Startup fail-fast probe for an explicitly enabled runtime. */
    public void probeAvailable() {
        jdbc.queryForList("SELECT 1 FROM " + leaseTable + " WHERE false");
        jdbc.queryForList("SELECT 1 FROM " + checkpointTable + " WHERE false");
    }

    /**
     * Atomically consumes one authorization envelope into one signed lease.
     * An exact retry returns the originally committed bytes; any key/request,
     * body, identity or authority mismatch fails closed.
     */
    public byte[] registerLease(ViewOnlyLeaseRecord lease) {
        Objects.requireNonNull(lease, "lease");
        try {
            byte[] response = transactions.execute(status -> {
                advisoryLock("lease:" + lease.authorizationEnvelopeSha256());
                LeaseRow byIdempotency = findLeaseBy("idempotency_key_sha256", lease.idempotencyKeySha256());
                if (byIdempotency != null) {
                    return exactLeaseRetry(byIdempotency, lease);
                }
                if (findLeaseBy("redeem_request_id", lease.redeemRequestId()) != null
                        || findLeaseBy("authorization_envelope_sha256", lease.authorizationEnvelopeSha256()) != null
                        || findLeaseBy("transaction_id_sha256", lease.transactionIdSha256()) != null) {
                    throw new ViewOnlyAuthorityException(
                            ViewOnlyAuthorityError.AUTHORITY_CONSUMED,
                            "authorization, transaction or request has already been redeemed");
                }
                int inserted = jdbc.update("INSERT INTO " + leaseTable + " ("
                                + "lease_id, redeem_request_id, idempotency_key_sha256, request_body_sha256,"
                                + " authorization_caller_identity_sha256, transaction_id_sha256, binding_sha256,"
                                + " binding_canonical_json, lease_envelope_sha256,"
                                + " evaluation_preflight_envelope_sha256, redemption_preflight_envelope_sha256,"
                                + " authorization_envelope_sha256, signed_lease_envelope, issued_at, expires_at,"
                                + " max_writes, write_count, closed)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,false)",
                        lease.leaseId(), lease.redeemRequestId(), lease.idempotencyKeySha256(),
                        lease.requestBodySha256(), lease.callerIdentitySha256(), lease.transactionIdSha256(),
                        lease.bindingSha256(), canonicalizer.canonicalString(lease.binding()),
                        lease.leaseEnvelopeSha256(), lease.evaluationPreflightEnvelopeSha256(),
                        lease.redemptionPreflightEnvelopeSha256(), lease.authorizationEnvelopeSha256(),
                        lease.signedLeaseEnvelope(), Timestamp.from(lease.issuedAt()), Timestamp.from(lease.expiresAt()),
                        lease.maxWrites());
                if (inserted != 1) {
                    throw unavailable("lease redemption did not insert exactly one row", null);
                }
                return lease.signedLeaseEnvelope();
            });
            return copyRequired(response);
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (DataAccessException databaseError) {
            throw unavailable("lease redemption store failed", databaseError);
        }
    }

    /**
     * Checks an already-verified redemption retry before live revalidation or
     * signing. Empty means no committed redemption exists. A present value is
     * the original byte-identical lease; any partial equality is a conflict.
     */
    public Optional<byte[]> findLeaseRetry(UUID redeemRequestId,
                                           String idempotencyKeySha256,
                                           String requestBodySha256,
                                           String callerIdentitySha256,
                                           String authorizationEnvelopeSha256,
                                           String transactionIdSha256) {
        Objects.requireNonNull(redeemRequestId, "redeemRequestId");
        ViewOnlyDigest.requireSha256(idempotencyKeySha256, "idempotencyKeySha256");
        ViewOnlyDigest.requireSha256(requestBodySha256, "requestBodySha256");
        ViewOnlyDigest.requireSha256(callerIdentitySha256, "callerIdentitySha256");
        ViewOnlyDigest.requireSha256(authorizationEnvelopeSha256, "authorizationEnvelopeSha256");
        ViewOnlyDigest.requireSha256(transactionIdSha256, "transactionIdSha256");
        try {
            LeaseRow byKey = findLeaseBy("idempotency_key_sha256", idempotencyKeySha256);
            if (byKey != null) {
                if (!byKey.redeemRequestId().equals(redeemRequestId)
                        || !byKey.requestBodySha256().equals(requestBodySha256)
                        || !byKey.callerIdentitySha256().equals(callerIdentitySha256)
                        || !byKey.authorizationEnvelopeSha256().equals(authorizationEnvelopeSha256)
                        || !byKey.transactionIdSha256().equals(transactionIdSha256)) {
                    throw conflict("lease retry key is bound to different verified request material");
                }
                return Optional.of(copyRequired(byKey.responseEnvelope()));
            }
            if (findLeaseBy("redeem_request_id", redeemRequestId) != null
                    || findLeaseBy("authorization_envelope_sha256", authorizationEnvelopeSha256) != null
                    || findLeaseBy("transaction_id_sha256", transactionIdSha256) != null) {
                throw new ViewOnlyAuthorityException(
                        ViewOnlyAuthorityError.AUTHORITY_CONSUMED,
                        "authorization, transaction or request has already been redeemed");
            }
            return Optional.empty();
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (DataAccessException databaseError) {
            throw unavailable("lease retry lookup failed", databaseError);
        }
    }

    /**
     * Creates one immutable checkpoint and signed receipt under a row-locked
     * lease. The signer is called only after all CAS invariants pass and inside
     * the same transaction that stores its exact output bytes.
     */
    public byte[] createCheckpoint(ViewOnlyCheckpointCommand command,
                                   ViewOnlyOidcCaller caller,
                                   ViewOnlyCheckpointReceiptSigner signer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(signer, "signer");
        if (!"executor".equals(caller.profile())) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.EXECUTOR_IDENTITY_MISMATCH,
                    "checkpoint create requires the executor OIDC profile");
        }
        String callerIdentity = caller.stableIdentitySha256(canonicalizer);
        if (!callerIdentity.equals(command.executorIdentitySha256())) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.EXECUTOR_IDENTITY_MISMATCH,
                    "checkpoint caller identity projection does not match the verified command");
        }
        try {
            byte[] response = transactions.execute(status -> {
                LeaseRow lease = lockLease(command.leaseId());
                validateLeaseBinding(lease, command);

                CheckpointRow priorByKey = findCheckpointBy(
                        "idempotency_key_sha256", command.idempotencyKeySha256());
                if (priorByKey != null) {
                    return exactCheckpointRetry(priorByKey, command);
                }
                if (findCheckpointBy("request_id", command.requestId()) != null
                        || findCheckpoint(command.transactionIdSha256(), command.sequence()) != null) {
                    throw conflict("request ID or transaction sequence is already bound to different material");
                }

                Instant now = clock.instant();
                if (!now.isBefore(lease.expiresAt())) {
                    throw new ViewOnlyAuthorityException(ViewOnlyAuthorityError.LEASE_EXPIRED, "checkpoint lease expired");
                }
                if (lease.closed()) {
                    throw new ViewOnlyAuthorityException(ViewOnlyAuthorityError.LEASE_CLOSED, "checkpoint lease is closed");
                }
                if (lease.writeCount() >= lease.maxWrites()) {
                    throw new ViewOnlyAuthorityException(
                            ViewOnlyAuthorityError.WRITE_LIMIT_EXCEEDED, "checkpoint lease write limit reached");
                }
                bindExecutor(lease, callerIdentity);
                validateChain(command);

                ViewOnlyCheckpointSigningInput signingInput = new ViewOnlyCheckpointSigningInput(
                        UUID.randomUUID(), command, canonicalizer.parseCanonical(lease.bindingCanonicalJson()),
                        lease.evaluationPreflightEnvelopeSha256(), lease.redemptionPreflightEnvelopeSha256(),
                        lease.authorizationEnvelopeSha256(), caller, lease.expiresAt());
                byte[] signedEnvelope;
                try {
                    signedEnvelope = signer.sign(signingInput);
                } catch (RuntimeException signingFailure) {
                    throw new ViewOnlyAuthorityException(
                            ViewOnlyAuthorityError.SIGNING_UNAVAILABLE,
                            "checkpoint receipt signer failed closed", signingFailure);
                }
                requireEnvelope(signedEnvelope, MAX_CHECKPOINT_ENVELOPE_BYTES, "checkpoint receipt");

                int inserted = jdbc.update("INSERT INTO " + checkpointTable + " ("
                                + "transaction_id_sha256, sequence, lease_id, request_id, idempotency_key_sha256,"
                                + " request_body_sha256, executor_identity_sha256, lease_envelope_sha256,"
                                + " binding_sha256, previous_state, state, reason_code, local_checkpoint_sha256,"
                                + " local_payload_sha256, previous_stored_object_sha256, stored_object_sha256,"
                                + " signed_receipt_envelope, terminal, created_at, expires_at)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        command.transactionIdSha256(), command.sequence(), command.leaseId(), command.requestId(),
                        command.idempotencyKeySha256(), command.requestBodySha256(), callerIdentity,
                        command.leaseEnvelopeSha256(), command.bindingSha256(),
                        command.previousState() == null ? null : command.previousState().name(),
                        command.state().name(), command.reasonCode(), command.localCheckpointSha256(),
                        command.localPayloadSha256(), command.previousStoredObjectSha256(),
                        command.storedObjectSha256(), signedEnvelope, command.terminal(),
                        Timestamp.from(command.createdAt()), Timestamp.from(lease.expiresAt()));
                if (inserted != 1) {
                    throw unavailable("checkpoint insert did not affect exactly one row", null);
                }
                int updated = jdbc.update("UPDATE " + leaseTable
                                + " SET write_count = write_count + 1, closed = ? WHERE lease_id = ?",
                        command.terminal(), command.leaseId());
                if (updated != 1) {
                    throw unavailable("checkpoint lease update did not affect exactly one row", null);
                }
                return signedEnvelope;
            });
            return copyRequired(response);
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (DataAccessException databaseError) {
            throw unavailable("checkpoint CAS store failed", databaseError);
        }
    }

    /** Reads only through the same lease and stable executor identity. */
    public ViewOnlyOidcBinding readOidcBinding(String transactionIdSha256) {
        ViewOnlyDigest.requireSha256(transactionIdSha256, "transactionIdSha256");
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT binding_canonical_json FROM " + leaseTable
                            + " WHERE transaction_id_sha256 = ?",
                    String.class, transactionIdSha256);
            if (rows.isEmpty()) {
                throw new ViewOnlyAuthorityException(
                        ViewOnlyAuthorityError.CHECKPOINT_NOT_FOUND, "checkpoint not found");
            }
            return ViewOnlyOidcBinding.fromJson(canonicalizer.strictParse(rows.get(0)));
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (DataAccessException databaseError) {
            throw unavailable("checkpoint binding read failed", databaseError);
        }
    }

    /** Reads only through the same lease and stable executor identity. */
    public byte[] readCheckpoint(String transactionIdSha256,
                                 int sequence,
                                 ViewOnlyOidcCaller caller) {
        ViewOnlyDigest.requireSha256(transactionIdSha256, "transactionIdSha256");
        Objects.requireNonNull(caller, "caller");
        if (sequence < 0 || sequence > 63 || !"executor".equals(caller.profile())) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID, "checkpoint read contract is invalid");
        }
        try {
            List<ReadRow> rows = jdbc.query("SELECT c.signed_receipt_envelope, l.expires_at,"
                            + " l.executor_identity_sha256 FROM " + checkpointTable + " c JOIN " + leaseTable
                            + " l ON l.lease_id = c.lease_id WHERE c.transaction_id_sha256 = ?"
                            + " AND c.sequence = ?",
                    (rs, rowNum) -> new ReadRow(
                            rs.getBytes(1), rs.getTimestamp(2).toInstant(), rs.getString(3)),
                    transactionIdSha256, sequence);
            if (rows.isEmpty()) {
                throw new ViewOnlyAuthorityException(
                        ViewOnlyAuthorityError.CHECKPOINT_NOT_FOUND, "checkpoint not found");
            }
            ReadRow row = rows.get(0);
            if (!clock.instant().isBefore(row.expiresAt())) {
                throw new ViewOnlyAuthorityException(ViewOnlyAuthorityError.LEASE_EXPIRED, "checkpoint lease expired");
            }
            if (!caller.stableIdentitySha256(canonicalizer).equals(row.executorIdentitySha256())) {
                throw new ViewOnlyAuthorityException(
                        ViewOnlyAuthorityError.EXECUTOR_IDENTITY_MISMATCH, "checkpoint read executor mismatch");
            }
            return copyRequired(row.responseEnvelope());
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (DataAccessException databaseError) {
            throw unavailable("checkpoint read store failed", databaseError);
        }
    }

    private byte[] exactLeaseRetry(LeaseRow stored, ViewOnlyLeaseRecord requested) {
        if (!stored.redeemRequestId().equals(requested.redeemRequestId())
                || !stored.requestBodySha256().equals(requested.requestBodySha256())
                || !stored.callerIdentitySha256().equals(requested.callerIdentitySha256())
                || !stored.authorizationEnvelopeSha256().equals(requested.authorizationEnvelopeSha256())
                || !stored.transactionIdSha256().equals(requested.transactionIdSha256())) {
            throw conflict("lease idempotency key is bound to different request, body, identity or authority");
        }
        return copyRequired(stored.responseEnvelope());
    }

    private byte[] exactCheckpointRetry(CheckpointRow stored, ViewOnlyCheckpointCommand requested) {
        if (!stored.requestId().equals(requested.requestId())
                || !stored.requestBodySha256().equals(requested.requestBodySha256())
                || !stored.executorIdentitySha256().equals(requested.executorIdentitySha256())
                || !stored.transactionIdSha256().equals(requested.transactionIdSha256())
                || stored.sequence() != requested.sequence()
                || !stored.storedObjectSha256().equals(requested.storedObjectSha256())) {
            throw conflict("checkpoint idempotency key is bound to different request, body, identity or sequence");
        }
        return copyRequired(stored.responseEnvelope());
    }

    private void validateLeaseBinding(LeaseRow lease, ViewOnlyCheckpointCommand command) {
        if (!lease.leaseId().equals(command.leaseId())
                || !lease.transactionIdSha256().equals(command.transactionIdSha256())
                || !lease.bindingSha256().equals(command.bindingSha256())
                || !lease.leaseEnvelopeSha256().equals(command.leaseEnvelopeSha256())) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.LEASE_BINDING_MISMATCH, "checkpoint does not match its verified lease");
        }
    }

    private void bindExecutor(LeaseRow lease, String callerIdentity) {
        if (lease.executorIdentitySha256() == null) {
            int updated = jdbc.update("UPDATE " + leaseTable
                            + " SET executor_identity_sha256 = ?"
                            + " WHERE lease_id = ? AND executor_identity_sha256 IS NULL",
                    callerIdentity, lease.leaseId());
            if (updated != 1) {
                throw unavailable("executor identity could not be bound", null);
            }
        } else if (!lease.executorIdentitySha256().equals(callerIdentity)) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.EXECUTOR_IDENTITY_MISMATCH,
                    "checkpoint lease is already bound to another executor identity");
        }
    }

    private void validateChain(ViewOnlyCheckpointCommand command) {
        CheckpointRow latest = latestCheckpoint(command.transactionIdSha256());
        if (latest == null) {
            ViewOnlyCheckpointStateMachine.validateInitial(
                    command.sequence(), command.previousState(), command.state(), command.terminal());
            return;
        }
        if (command.sequence() != latest.sequence() + 1) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.SEQUENCE_CONFLICT, "checkpoint sequence must increment by exactly one");
        }
        if (command.previousState() != latest.state()
                || !Objects.equals(command.previousStoredObjectSha256(), latest.storedObjectSha256())) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.PREVIOUS_CHECKPOINT_MISMATCH,
                    "checkpoint predecessor state or stored-object digest does not match");
        }
        ViewOnlyCheckpointStateMachine.validateTransition(
                command.previousState(), command.state(), command.terminal());
    }

    private LeaseRow lockLease(UUID leaseId) {
        List<LeaseRow> rows = jdbc.query("SELECT lease_id, redeem_request_id, idempotency_key_sha256,"
                        + " request_body_sha256, authorization_caller_identity_sha256, transaction_id_sha256,"
                        + " binding_sha256, binding_canonical_json, lease_envelope_sha256,"
                        + " evaluation_preflight_envelope_sha256, redemption_preflight_envelope_sha256,"
                        + " authorization_envelope_sha256, signed_lease_envelope, issued_at, expires_at,"
                        + " max_writes, write_count, closed, executor_identity_sha256 FROM " + leaseTable
                        + " WHERE lease_id = ? FOR UPDATE",
                (rs, rowNum) -> mapLease(rs), leaseId);
        if (rows.isEmpty()) {
            throw new ViewOnlyAuthorityException(ViewOnlyAuthorityError.LEASE_NOT_FOUND, "checkpoint lease not found");
        }
        return rows.get(0);
    }

    private LeaseRow findLeaseBy(String column, Object value) {
        if (!List.of("idempotency_key_sha256", "redeem_request_id", "authorization_envelope_sha256",
                "transaction_id_sha256").contains(column)) {
            throw new IllegalArgumentException("unsupported lease lookup");
        }
        List<LeaseRow> rows = jdbc.query("SELECT lease_id, redeem_request_id, idempotency_key_sha256,"
                        + " request_body_sha256, authorization_caller_identity_sha256, transaction_id_sha256,"
                        + " binding_sha256, binding_canonical_json, lease_envelope_sha256,"
                        + " evaluation_preflight_envelope_sha256, redemption_preflight_envelope_sha256,"
                        + " authorization_envelope_sha256, signed_lease_envelope, issued_at, expires_at,"
                        + " max_writes, write_count, closed, executor_identity_sha256 FROM " + leaseTable
                        + " WHERE " + column + " = ?",
                (rs, rowNum) -> mapLease(rs), value);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CheckpointRow latestCheckpoint(String transactionIdSha256) {
        List<CheckpointRow> rows = jdbc.query("SELECT transaction_id_sha256, sequence, request_id,"
                        + " idempotency_key_sha256, request_body_sha256, executor_identity_sha256, state,"
                        + " stored_object_sha256, signed_receipt_envelope FROM " + checkpointTable
                        + " WHERE transaction_id_sha256 = ? ORDER BY sequence DESC LIMIT 1",
                (rs, rowNum) -> mapCheckpoint(rs), transactionIdSha256);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CheckpointRow findCheckpoint(String transactionIdSha256, int sequence) {
        List<CheckpointRow> rows = jdbc.query("SELECT transaction_id_sha256, sequence, request_id,"
                        + " idempotency_key_sha256, request_body_sha256, executor_identity_sha256, state,"
                        + " stored_object_sha256, signed_receipt_envelope FROM " + checkpointTable
                        + " WHERE transaction_id_sha256 = ? AND sequence = ?",
                (rs, rowNum) -> mapCheckpoint(rs), transactionIdSha256, sequence);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CheckpointRow findCheckpointBy(String column, Object value) {
        if (!List.of("idempotency_key_sha256", "request_id").contains(column)) {
            throw new IllegalArgumentException("unsupported checkpoint lookup");
        }
        List<CheckpointRow> rows = jdbc.query("SELECT transaction_id_sha256, sequence, request_id,"
                        + " idempotency_key_sha256, request_body_sha256, executor_identity_sha256, state,"
                        + " stored_object_sha256, signed_receipt_envelope FROM " + checkpointTable
                        + " WHERE " + column + " = ?",
                (rs, rowNum) -> mapCheckpoint(rs), value);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void advisoryLock(String value) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, value);
    }

    private static LeaseRow mapLease(ResultSet rs) throws SQLException {
        return new LeaseRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getString(12), rs.getBytes(13),
                rs.getTimestamp(14).toInstant(), rs.getTimestamp(15).toInstant(), rs.getInt(16), rs.getInt(17),
                rs.getBoolean(18), rs.getString(19));
    }

    private static CheckpointRow mapCheckpoint(ResultSet rs) throws SQLException {
        return new CheckpointRow(
                rs.getString(1), rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), ViewOnlyCheckpointState.valueOf(rs.getString(7)), rs.getString(8), rs.getBytes(9));
    }

    private static void requireEnvelope(byte[] envelope, int maximum, String label) {
        if (envelope == null || envelope.length == 0 || envelope.length > maximum) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.SIGNING_UNAVAILABLE, label + " is empty or exceeds its hard size limit");
        }
    }

    private static byte[] copyRequired(byte[] value) {
        if (value == null || value.length == 0) {
            throw unavailable("stored signed envelope is unavailable", null);
        }
        return Arrays.copyOf(value, value.length);
    }

    private static ViewOnlyAuthorityException conflict(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.IDEMPOTENCY_CONFLICT, message);
    }

    private static ViewOnlyAuthorityException unavailable(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(
                ViewOnlyAuthorityError.CHECKPOINT_STORE_UNAVAILABLE, message, cause);
    }

    private record LeaseRow(
            UUID leaseId,
            UUID redeemRequestId,
            String idempotencyKeySha256,
            String requestBodySha256,
            String callerIdentitySha256,
            String transactionIdSha256,
            String bindingSha256,
            String bindingCanonicalJson,
            String leaseEnvelopeSha256,
            String evaluationPreflightEnvelopeSha256,
            String redemptionPreflightEnvelopeSha256,
            String authorizationEnvelopeSha256,
            byte[] responseEnvelope,
            Instant issuedAt,
            Instant expiresAt,
            int maxWrites,
            int writeCount,
            boolean closed,
            String executorIdentitySha256) {
    }

    private record CheckpointRow(
            String transactionIdSha256,
            int sequence,
            UUID requestId,
            String idempotencyKeySha256,
            String requestBodySha256,
            String executorIdentitySha256,
            ViewOnlyCheckpointState state,
            String storedObjectSha256,
            byte[] responseEnvelope) {
    }

    private record ReadRow(byte[] responseEnvelope, Instant expiresAt, String executorIdentitySha256) {
    }
}
