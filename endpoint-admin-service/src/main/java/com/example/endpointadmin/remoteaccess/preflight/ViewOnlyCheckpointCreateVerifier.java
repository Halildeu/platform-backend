package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Strict HTTP-body, schema-shape, DSSE-lease, OIDC and content-address verifier for checkpoint create. */
public final class ViewOnlyCheckpointCreateVerifier {
    public static final int MAX_REQUEST_BYTES = 524_288;
    private static final String SCHEMA_VERSION = "faz22.6.viewOnlyExternalCheckpointCreate.v1";
    private static final String STORED_OBJECT_DOMAIN = "faz22.6/view-only/checkpoint-stored-object/v1";
    private static final String DSSE_ENVELOPE_DOMAIN = "faz22.6/view-only/dsse-envelope/v1";
    private static final Set<String> EXACT_FIELDS = Set.of(
            "schemaVersion", "requestId", "leaseEnvelope", "transactionIdSha256", "bindingSha256",
            "sequence", "previousState", "state", "reasonCode", "localCheckpointSha256",
            "localPayloadSha256", "previousStoredObjectSha256", "idempotencyKeySha256", "terminal");

    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyDigest digest;
    private final ViewOnlyLeaseEnvelopeVerifier leaseVerifier;
    private final ViewOnlyOidcCallerFactory callerFactory;
    private final Clock clock;
    private final String checkpointCreateIdempotencyDomain;

    public ViewOnlyCheckpointCreateVerifier(RemoteViewJsonCanonicalizer canonicalizer,
                                            ViewOnlyLeaseEnvelopeVerifier leaseVerifier,
                                            ViewOnlyOidcCallerFactory callerFactory,
                                            Clock clock,
                                            String checkpointCreateIdempotencyDomain) {
        this.canonicalizer = canonicalizer;
        this.digest = new ViewOnlyDigest(canonicalizer);
        this.leaseVerifier = leaseVerifier;
        this.callerFactory = callerFactory;
        this.clock = clock;
        if (checkpointCreateIdempotencyDomain == null || checkpointCreateIdempotencyDomain.isBlank()) {
            throw new IllegalArgumentException("canonical checkpoint-create idempotency domain is required");
        }
        this.checkpointCreateIdempotencyDomain = checkpointCreateIdempotencyDomain;
    }

    public VerifiedViewOnlyCheckpointCreate verify(byte[] rawRequest, Jwt jwt) {
        if (rawRequest == null || rawRequest.length == 0 || rawRequest.length > MAX_REQUEST_BYTES) {
            throw invalid("checkpoint request body is empty or exceeds 512 KiB");
        }
        JsonNode request = strictParse(decodeUtf8(rawRequest));
        requireObjectWithExactFields(request);
        requireText(request, "schemaVersion", SCHEMA_VERSION);

        UUID requestId = uuid(request, "requestId");
        VerifiedViewOnlyLeaseEnvelope lease = leaseVerifier.verify(
                requiredObject(request, "leaseEnvelope"), clock.instant());
        if (lease.closed() || !clock.instant().isBefore(lease.expiresAt())) {
            throw new ViewOnlyAuthorityException(
                    lease.closed() ? ViewOnlyAuthorityError.LEASE_CLOSED : ViewOnlyAuthorityError.LEASE_EXPIRED,
                    "verified checkpoint lease is closed or expired");
        }
        requireText(request, "transactionIdSha256", lease.transactionIdSha256());
        requireText(request, "bindingSha256", lease.bindingSha256());

        ViewOnlyOidcCaller caller = callerFactory.create(
                jwt, ViewOnlyGithubOidcProfile.EXECUTOR, lease.oidcBinding());
        String callerIdentitySha256 = caller.stableIdentitySha256(canonicalizer);

        ObjectNode withoutIdempotency = ((ObjectNode) request).deepCopy();
        withoutIdempotency.remove("idempotencyKeySha256");
        String requestBodySha256 = digest.bodyDigest(withoutIdempotency);
        ObjectNode idempotencyProjection = canonicalizer.mapper().createObjectNode();
        idempotencyProjection.put("domain", checkpointCreateIdempotencyDomain);
        idempotencyProjection.put("requestId", requestId.toString());
        idempotencyProjection.put("bodySha256", requestBodySha256);
        idempotencyProjection.set("identity", caller.stableIdentityProjection(canonicalizer));
        String idempotencyKeySha256 = canonicalizer.digest(idempotencyProjection);
        requireText(request, "idempotencyKeySha256", idempotencyKeySha256);

        ObjectNode storedProjection = ((ObjectNode) request).deepCopy();
        storedProjection.remove("leaseEnvelope");
        String storedObjectSha256 = digest.domainDigest(
                STORED_OBJECT_DOMAIN, "request", storedProjection);

        int sequence = integer(request, "sequence", 0, 63);
        ViewOnlyCheckpointState previousState = nullableState(request.get("previousState"));
        ViewOnlyCheckpointState state = state(request.get("state"));
        String previousStored = nullableDigest(request.get("previousStoredObjectSha256"));
        boolean terminal = booleanValue(request, "terminal");
        ViewOnlyCheckpointCommand command = new ViewOnlyCheckpointCommand(
                requestId, lease.leaseId(), lease.leaseEnvelopeSha256(), lease.transactionIdSha256(),
                lease.bindingSha256(), sequence, previousState, state, text(request, "reasonCode"),
                digestText(request, "localCheckpointSha256"), digestText(request, "localPayloadSha256"),
                previousStored, storedObjectSha256, idempotencyKeySha256, requestBodySha256,
                callerIdentitySha256, terminal, clock.instant());
        return new VerifiedViewOnlyCheckpointCreate(command, caller);
    }

    /**
     * Builds a committed-response lookup key from strict request bytes, a
     * durable stored transaction binding and current GitHub OIDC identity.
     * It does not apply current lease expiry/revocation; those remain required
     * for every new mutation in {@link #verify(byte[], Jwt)}.
     */
    public ViewOnlyCheckpointRetryCandidate retryCandidate(
            byte[] rawRequest, Jwt jwt, ViewOnlyOidcBinding durableBinding) {
        if (rawRequest == null || rawRequest.length == 0 || rawRequest.length > MAX_REQUEST_BYTES) {
            throw invalid("checkpoint request body is empty or exceeds 512 KiB");
        }
        JsonNode request = strictParse(decodeUtf8(rawRequest));
        requireObjectWithExactFields(request);
        requireText(request, "schemaVersion", SCHEMA_VERSION);
        UUID requestId = uuid(request, "requestId");
        String transactionIdSha256 = digestText(request, "transactionIdSha256");
        int sequence = integer(request, "sequence", 0, 63);
        ViewOnlyOidcCaller caller = callerFactory.create(
                jwt, ViewOnlyGithubOidcProfile.EXECUTOR, durableBinding);
        String callerIdentitySha256 = caller.stableIdentitySha256(canonicalizer);

        ObjectNode withoutIdempotency = ((ObjectNode) request).deepCopy();
        withoutIdempotency.remove("idempotencyKeySha256");
        String requestBodySha256 = digest.bodyDigest(withoutIdempotency);
        ObjectNode idempotencyProjection = canonicalizer.mapper().createObjectNode();
        idempotencyProjection.put("domain", checkpointCreateIdempotencyDomain);
        idempotencyProjection.put("requestId", requestId.toString());
        idempotencyProjection.put("bodySha256", requestBodySha256);
        idempotencyProjection.set("identity", caller.stableIdentityProjection(canonicalizer));
        String idempotencyKeySha256 = canonicalizer.digest(idempotencyProjection);
        requireText(request, "idempotencyKeySha256", idempotencyKeySha256);

        ObjectNode storedProjection = ((ObjectNode) request).deepCopy();
        storedProjection.remove("leaseEnvelope");
        String storedObjectSha256 = digest.domainDigest(
                STORED_OBJECT_DOMAIN, "request", storedProjection);
        String leaseEnvelopeSha256 = digest.domainDigest(
                DSSE_ENVELOPE_DOMAIN, "envelope", requiredObject(request, "leaseEnvelope"));
        return new ViewOnlyCheckpointRetryCandidate(
                requestId, idempotencyKeySha256, requestBodySha256, callerIdentitySha256,
                leaseEnvelopeSha256, transactionIdSha256, sequence, storedObjectSha256);
    }

    public String transactionIdForRetry(byte[] rawRequest) {
        if (rawRequest == null || rawRequest.length == 0 || rawRequest.length > MAX_REQUEST_BYTES) {
            throw invalid("checkpoint request body is empty or exceeds 512 KiB");
        }
        JsonNode request = strictParse(decodeUtf8(rawRequest));
        requireObjectWithExactFields(request);
        requireText(request, "schemaVersion", SCHEMA_VERSION);
        return digestText(request, "transactionIdSha256");
    }

    private JsonNode strictParse(String raw) {
        try {
            return canonicalizer.strictParse(raw);
        } catch (com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyException invalidJson) {
            throw invalid("checkpoint request is not strict canonical JSON");
        }
    }

    private static String decodeUtf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw invalid("checkpoint request must be canonical UTF-8 text");
        }
    }

    private static void requireObjectWithExactFields(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid("checkpoint request must be a JSON object");
        }
        Set<String> fields = new HashSet<>();
        Iterator<String> names = request.fieldNames();
        names.forEachRemaining(fields::add);
        if (!fields.equals(EXACT_FIELDS)) {
            throw invalid("checkpoint request fields do not match the exact schema");
        }
    }

    private static JsonNode requiredObject(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(field + " must be an object");
        }
        return value;
    }

    private static UUID uuid(JsonNode request, String field) {
        try {
            return UUID.fromString(text(request, field));
        } catch (IllegalArgumentException invalidUuid) {
            throw invalid(field + " must be a UUID");
        }
    }

    private static int integer(JsonNode request, String field, int minimum, int maximum) {
        JsonNode value = request.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw invalid(field + " is outside its integer range");
        }
        return value.intValue();
    }

    private static boolean booleanValue(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static String digestText(JsonNode request, String field) {
        return ViewOnlyDigest.requireSha256(text(request, field), field);
    }

    private static String nullableDigest(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("previousStoredObjectSha256 must be null or a digest");
        }
        return ViewOnlyDigest.requireSha256(value.textValue(), "previousStoredObjectSha256");
    }

    private static ViewOnlyCheckpointState nullableState(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return state(value);
    }

    private static ViewOnlyCheckpointState state(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid("checkpoint state must be text");
        }
        try {
            return ViewOnlyCheckpointState.valueOf(value.textValue());
        } catch (IllegalArgumentException unknown) {
            throw invalid("checkpoint state is unknown");
        }
    }

    private static void requireText(JsonNode request, String field, String expected) {
        if (!expected.equals(text(request, field))) {
            throw invalid(field + " does not match verified authority");
        }
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message);
    }
}
