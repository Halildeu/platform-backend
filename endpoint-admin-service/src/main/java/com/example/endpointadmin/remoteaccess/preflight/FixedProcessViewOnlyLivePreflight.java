package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Concrete, fixed-binary boundary for the twelve read-only live checks.
 *
 * <p>The executable path and bytes are pinned, root-owned and never selected
 * by the caller. It receives only canonical public request/binding and hashed
 * OIDC projections on stdin; environment inheritance, shell expansion, raw
 * JWT and credential arguments are forbidden. Its signed output is always
 * re-verified in-process against the independent runtime trust root.</p>
 */
public final class FixedProcessViewOnlyLivePreflight
        implements ViewOnlyLivePreflightService, ViewOnlyLivePreflightRevalidator {
    private static final int MAX_EXECUTABLE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 524_288;
    private static final String IDEMPOTENCY_DOMAIN = "faz22.6/view-only/live-preflight-idempotency/v1";
    private static final String REQUEST_DOMAIN = "faz22.6/view-only/live-preflight-request/v1";
    private static final List<String> REQUIRED_CHECKS = List.of(
            "targetIdentity", "pkceAuthorizationCode", "tokenRefresh", "routeApi", "browserConsole",
            "replayIsolation", "clusterContext", "portsTunnels", "imageDigests", "policyMask",
            "runnerCapacity", "watchdogRollback");
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "schemaVersion", "requestId", "idempotencyKeySha256", "bindingHandoffEnvelope",
            "requestedChecks");

    private final Path executable;
    private final String executableSha256;
    private final Duration timeout;
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyDigest digest;
    private final StrictViewOnlyBindingHandoffVerifier bindingVerifier;
    private final StrictViewOnlyPreflightEnvelopeVerifier receiptVerifier;
    private final ViewOnlyOidcCallerFactory callerFactory;
    private final Clock clock;

    public FixedProcessViewOnlyLivePreflight(ViewOnlyAuthorityProperties properties,
                                             RemoteViewJsonCanonicalizer canonicalizer,
                                             StrictViewOnlyBindingHandoffVerifier bindingVerifier,
                                             StrictViewOnlyPreflightEnvelopeVerifier receiptVerifier,
                                             ViewOnlyOidcCallerFactory callerFactory,
                                             Clock clock) {
        properties.validateActivation();
        this.executable = Path.of(properties.getFixedPreflightExecutableFile()).toAbsolutePath().normalize();
        this.executableSha256 = properties.getFixedPreflightExecutableSha256();
        this.timeout = Duration.ofSeconds(properties.getFixedPreflightTimeoutSeconds());
        this.canonicalizer = canonicalizer;
        this.digest = new ViewOnlyDigest(canonicalizer);
        this.bindingVerifier = bindingVerifier;
        this.receiptVerifier = receiptVerifier;
        this.callerFactory = callerFactory;
        this.clock = clock;
    }

    public void probeReady() {
        verifyExecutable();
    }

    @Override
    public byte[] attest(byte[] rawRequest, Jwt jwt) {
        if (rawRequest == null || rawRequest.length == 0 || rawRequest.length > 262_144) {
            throw invalid("live preflight request is empty or exceeds 256 KiB");
        }
        JsonNode request = parse(rawRequest, "live preflight request");
        exactFields(request, REQUEST_FIELDS, "live preflight request");
        requireText(request, "schemaVersion", "faz22.6.viewOnlyLivePreflightRequest.v1");
        UUID requestId = uuid(request, "requestId");
        JsonNode requestedChecks = request.get("requestedChecks");
        if (requestedChecks == null || !requestedChecks.isArray() || requestedChecks.size() != REQUIRED_CHECKS.size()) {
            throw invalid("live preflight must request the exact twelve checks");
        }
        for (int index = 0; index < REQUIRED_CHECKS.size(); index++) {
            if (!requestedChecks.get(index).isTextual()
                    || !REQUIRED_CHECKS.get(index).equals(requestedChecks.get(index).textValue())) {
                throw invalid("live preflight check order differs from the fixed contract");
            }
        }
        StrictViewOnlyBindingHandoffVerifier.VerifiedBindingHandoff handoff = bindingVerifier.verify(
                object(request, "bindingHandoffEnvelope"), clock.instant());
        ViewOnlyOidcCaller caller = callerFactory.create(
                jwt, ViewOnlyGithubOidcProfile.PREFLIGHT, ViewOnlyOidcBinding.fromJson(handoff.binding()));
        ObjectNode withoutKey = ((ObjectNode) request).deepCopy();
        withoutKey.remove("idempotencyKeySha256");
        String bodySha256 = canonicalizer.digest(withoutKey);
        ObjectNode idempotency = canonicalizer.mapper().createObjectNode();
        idempotency.put("domain", IDEMPOTENCY_DOMAIN);
        idempotency.put("requestId", requestId.toString());
        idempotency.put("bodySha256", bodySha256);
        idempotency.set("identity", caller.stableIdentityProjection(canonicalizer));
        requireText(request, "idempotencyKeySha256", canonicalizer.digest(idempotency));

        ObjectNode processInput = canonicalizer.mapper().createObjectNode();
        processInput.put("schemaVersion", "faz22.6.viewOnlyFixedPreflightProcessInput.v1");
        processInput.put("mode", "attest");
        processInput.set("request", request);
        processInput.set("caller", processCaller(caller));
        byte[] signedEnvelope = execute("attest", canonicalizer.canonicalBytes(processInput));
        VerifiedViewOnlyPreflightReceipt verified = receiptVerifier.verifyEvaluation(
                parse(signedEnvelope, "signed preflight response"), clock.instant());
        if (!handoff.bindingSha256().equals(verified.bindingSha256())
                || !handoff.transactionIdSha256().equals(verified.transactionIdSha256())) {
            throw invalid("signed preflight response differs from the coordinator binding");
        }
        JsonNode payload = decodePayload(signedEnvelope);
        requireText(payload, "requestSha256", digest.domainDigest(REQUEST_DOMAIN, "request", request));
        requireText(payload, "idempotencyKeySha256", text(request, "idempotencyKeySha256"));
        requireText(payload, "bindingHandoffEnvelopeSha256", handoff.envelopeSha256());
        requireText(payload, "requestId", requestId.toString());
        return signedEnvelope;
    }

    @Override
    public VerifiedViewOnlyPreflightReceipt revalidate(JsonNode bindingValue, ViewOnlyOidcCaller authorizationCaller) {
        JsonNode binding = ViewOnlyTransactionBinding.requireExact(bindingValue);
        if (!"authorization".equals(authorizationCaller.profile())
                || authorizationCaller.actorId() != binding.get("triggeringActorId").longValue()
                || authorizationCaller.runId() != binding.get("runId").longValue()
                || authorizationCaller.runAttempt() != 1
                || !authorizationCaller.ref().equals(text(binding, "intentRef"))
                || !authorizationCaller.headSha().equals(text(binding, "headSha"))) {
            throw invalid("redemption revalidation caller differs from the signed binding");
        }
        ObjectNode processInput = canonicalizer.mapper().createObjectNode();
        processInput.put("schemaVersion", "faz22.6.viewOnlyFixedPreflightProcessInput.v1");
        processInput.put("mode", "revalidate");
        processInput.set("binding", binding);
        processInput.set("caller", processCaller(authorizationCaller));
        byte[] signedEnvelope = execute("revalidate", canonicalizer.canonicalBytes(processInput));
        VerifiedViewOnlyPreflightReceipt verified = receiptVerifier.verifyEvaluation(
                parse(signedEnvelope, "signed revalidation response"), clock.instant());
        String bindingSha256 = digest.domainDigest(
                "faz22.6/view-only/transaction-binding/v1", "binding", binding);
        String transactionIdSha256 = digest.domainDigest(
                "faz22.6/view-only/transaction-id/v1", "binding", binding);
        if (!bindingSha256.equals(verified.bindingSha256())
                || !transactionIdSha256.equals(verified.transactionIdSha256())) {
            throw invalid("redemption revalidation response differs from the signed binding");
        }
        return verified;
    }

    private byte[] execute(String mode, byte[] input) {
        verifyExecutable();
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), mode);
        builder.environment().clear();
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = builder.start();
        } catch (Exception startFailure) {
            throw unavailable("fixed preflight executable could not start", startFailure);
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> output = executor.submit(() -> {
                try (InputStream stream = process.getInputStream()) {
                    return stream.readNBytes(MAX_OUTPUT_BYTES + 1);
                }
            });
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(input);
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw unavailable("fixed preflight exceeded its hard timeout", null);
            }
            byte[] result = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || result.length == 0 || result.length > MAX_OUTPUT_BYTES) {
                Arrays.fill(result, (byte) 0);
                throw unavailable("fixed preflight failed or returned an out-of-bound response", null);
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw unavailable("fixed preflight was interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            process.destroyForcibly();
            throw unavailable("fixed preflight response could not be collected", failure);
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (Exception failure) {
            process.destroyForcibly();
            throw unavailable("fixed preflight process failed closed", failure);
        }
    }

    private void verifyExecutable() {
        byte[] bytes = null;
        try {
            PosixFileAttributes attributes = Files.readAttributes(
                    executable, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> permissions = attributes.permissions();
            if (!attributes.isRegularFile() || !"root".equals(attributes.owner().getName())
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || (!permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    && !permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    && !permissions.contains(PosixFilePermission.OTHERS_EXECUTE))) {
                throw unavailable("fixed preflight executable ownership or mode is unsafe", null);
            }
            try (SeekableByteChannel channel = Files.newByteChannel(
                    executable, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream stream = Channels.newInputStream(channel)) {
                bytes = stream.readNBytes(MAX_EXECUTABLE_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_EXECUTABLE_BYTES
                    || !executableSha256.equals("sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)))) {
                throw unavailable("fixed preflight executable bytes differ from the exact pin", null);
            }
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (Exception failure) {
            throw unavailable("fixed preflight executable is unavailable", failure);
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private ObjectNode processCaller(ViewOnlyOidcCaller caller) {
        ObjectNode value = caller.receiptProjection(canonicalizer);
        value.set("stableIdentity", caller.stableIdentityProjection(canonicalizer));
        return value;
    }

    private JsonNode decodePayload(byte[] envelopeBytes) {
        JsonNode envelope = parse(envelopeBytes, "signed preflight envelope");
        try {
            return canonicalizer.strictParse(new String(
                    java.util.Base64.getDecoder().decode(text(envelope, "payload")),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalidPayload) {
            throw invalid("signed preflight payload is not strict base64 JSON", invalidPayload);
        }
    }

    private JsonNode parse(byte[] value, String label) {
        try {
            return canonicalizer.strictParse(new String(value, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalidJson) {
            throw invalid(label + " is not strict JSON", invalidJson);
        }
    }

    private static void exactFields(JsonNode object, Set<String> expected, String label) {
        if (object == null || !object.isObject()) {
            throw invalid(label + " must be an object");
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> names = object.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid(label + " fields do not match the exact contract");
        }
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(field + " must be an object");
        }
        return value;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static void requireText(JsonNode object, String field, String expected) {
        if (!expected.equals(text(object, field))) {
            throw invalid(field + " does not match verified authority");
        }
    }

    private static UUID uuid(JsonNode object, String field) {
        try {
            return UUID.fromString(text(object, field));
        } catch (Exception invalidUuid) {
            throw invalid(field + " is not a UUID", invalidUuid);
        }
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return invalid(message, null);
    }

    private static ViewOnlyAuthorityException invalid(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message, cause);
    }

    private static ViewOnlyAuthorityException unavailable(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.PREFLIGHT_UNAVAILABLE, message, cause);
    }
}
