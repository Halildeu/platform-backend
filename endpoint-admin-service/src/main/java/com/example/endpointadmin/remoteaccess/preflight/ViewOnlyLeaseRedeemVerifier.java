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
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Strict verifier for the protected Environment checkpoint-lease redemption request. */
public final class ViewOnlyLeaseRedeemVerifier {
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final String SCHEMA_VERSION = "faz22.6.viewOnlyCheckpointLeaseRedeem.v1";
    private static final String IDEMPOTENCY_DOMAIN =
            "faz22.6/view-only/checkpoint-lease-redeem-idempotency/v1";
    private static final String BINDING_DOMAIN = "faz22.6/view-only/transaction-binding/v1";
    private static final String TRANSACTION_DOMAIN = "faz22.6/view-only/transaction-id/v1";
    private static final Set<String> EXACT_FIELDS = Set.of(
            "schemaVersion", "requestId", "idempotencyKeySha256", "binding", "bindingSha256",
            "transactionIdSha256", "evaluationPreflightReceiptEnvelope", "authorizationEnvelope",
            "authorizationPayloadType", "requestedTtlSeconds", "requestedMaxWrites");

    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyDigest digest;
    private final ViewOnlyPreflightEnvelopeVerifier preflightVerifier;
    private final ViewOnlyAuthorizationEnvelopeVerifier authorizationVerifier;
    private final ViewOnlyOidcCallerFactory callerFactory;
    private final Clock clock;

    public ViewOnlyLeaseRedeemVerifier(RemoteViewJsonCanonicalizer canonicalizer,
                                       ViewOnlyPreflightEnvelopeVerifier preflightVerifier,
                                       ViewOnlyAuthorizationEnvelopeVerifier authorizationVerifier,
                                       ViewOnlyOidcCallerFactory callerFactory,
                                       Clock clock) {
        this.canonicalizer = canonicalizer;
        this.digest = new ViewOnlyDigest(canonicalizer);
        this.preflightVerifier = preflightVerifier;
        this.authorizationVerifier = authorizationVerifier;
        this.callerFactory = callerFactory;
        this.clock = clock;
    }

    public VerifiedViewOnlyLeaseRedeem verify(byte[] rawRequest, Jwt jwt) {
        if (rawRequest == null || rawRequest.length == 0 || rawRequest.length > MAX_REQUEST_BYTES) {
            throw invalid("lease redemption body is empty or exceeds 1 MiB");
        }
        JsonNode request = canonicalizer.strictParse(decodeUtf8(rawRequest));
        requireExactFields(request);
        requireText(request, "schemaVersion", SCHEMA_VERSION);
        requireText(request, "authorizationPayloadType", VerifiedViewOnlyAuthorization.PAYLOAD_TYPE);
        UUID requestId = uuid(request, "requestId");
        JsonNode binding = object(request, "binding");
        String bindingSha256 = digest.domainDigest(BINDING_DOMAIN, "binding", binding);
        String transactionIdSha256 = digest.domainDigest(TRANSACTION_DOMAIN, "binding", binding);
        requireText(request, "bindingSha256", bindingSha256);
        requireText(request, "transactionIdSha256", transactionIdSha256);

        Instant now = clock.instant();
        VerifiedViewOnlyPreflightReceipt evaluation = preflightVerifier.verifyEvaluation(
                object(request, "evaluationPreflightReceiptEnvelope"), now);
        VerifiedViewOnlyAuthorization authorization = authorizationVerifier.verify(
                object(request, "authorizationEnvelope"), now);
        requireReceiptBinding(evaluation.bindingSha256(), evaluation.transactionIdSha256(),
                bindingSha256, transactionIdSha256, "evaluation preflight");
        requireReceiptBinding(authorization.bindingSha256(), authorization.transactionIdSha256(),
                bindingSha256, transactionIdSha256, "authorization");
        if (evaluation.issuedAt().isAfter(now)
                || Duration.between(evaluation.issuedAt(), now).compareTo(Duration.ofSeconds(7200)) > 0) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID,
                    "evaluation preflight exceeds the 7200-second redemption window");
        }
        if (!now.isBefore(authorization.expiresAt())) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.AUTHORITY_CONSUMED, "authorization envelope expired");
        }

        ViewOnlyOidcBinding oidcBinding = ViewOnlyOidcBinding.fromJson(binding);
        ViewOnlyOidcCaller caller = callerFactory.create(
                jwt, ViewOnlyGithubOidcProfile.AUTHORIZATION, oidcBinding);
        String callerIdentity = caller.stableIdentitySha256(canonicalizer);
        ObjectNode withoutKey = ((ObjectNode) request).deepCopy();
        withoutKey.remove("idempotencyKeySha256");
        String requestBodySha256 = digest.bodyDigest(withoutKey);
        ObjectNode idempotency = canonicalizer.mapper().createObjectNode();
        idempotency.put("domain", IDEMPOTENCY_DOMAIN);
        idempotency.put("requestId", requestId.toString());
        idempotency.put("bodySha256", requestBodySha256);
        idempotency.set("identity", caller.stableIdentityProjection(canonicalizer));
        String idempotencyKey = canonicalizer.digest(idempotency);
        requireText(request, "idempotencyKeySha256", idempotencyKey);

        int requestedTtl = integer(request, "requestedTtlSeconds", 900, 7200);
        int requestedMaxWrites = integer(request, "requestedMaxWrites", 64, 64);
        return new VerifiedViewOnlyLeaseRedeem(
                new ViewOnlyLeaseRedeemCommand(
                        requestId, idempotencyKey, requestBodySha256, callerIdentity, binding,
                        bindingSha256, transactionIdSha256, evaluation, authorization,
                        requestedTtl, requestedMaxWrites),
                caller);
    }

    private static void requireReceiptBinding(String receiptBinding,
                                              String receiptTransaction,
                                              String binding,
                                              String transaction,
                                              String label) {
        if (!binding.equals(receiptBinding) || !transaction.equals(receiptTransaction)) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.LEASE_BINDING_MISMATCH, label + " binding does not match request");
        }
    }

    private static String decodeUtf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw invalid("lease redemption must be strict UTF-8");
        }
    }

    private static void requireExactFields(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid("lease redemption must be a JSON object");
        }
        Set<String> fields = new HashSet<>();
        Iterator<String> names = request.fieldNames();
        names.forEachRemaining(fields::add);
        if (!fields.equals(EXACT_FIELDS)) {
            throw invalid("lease redemption fields do not match the exact schema");
        }
    }

    private static JsonNode object(JsonNode request, String field) {
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
            throw invalid(field + " is outside its exact integer range");
        }
        return value.intValue();
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.textValue();
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
