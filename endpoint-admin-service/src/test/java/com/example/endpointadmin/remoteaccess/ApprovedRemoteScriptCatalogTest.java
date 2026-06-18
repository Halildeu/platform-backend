package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.ApprovedRemoteScriptCatalog.ArgumentSpec;
import com.example.endpointadmin.remoteaccess.ApprovedRemoteScriptCatalog.ArgumentType;
import com.example.endpointadmin.remoteaccess.ApprovedRemoteScriptCatalog.Definition;
import com.example.endpointadmin.remoteaccess.ApprovedRemoteScriptCatalog.Invocation;
import com.example.endpointadmin.remoteaccess.ApprovedRemoteScriptCatalog.ResolutionStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedRemoteScriptCatalogTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "33333333-3333-3333-3333-333333333333";
    private static final long NOW = 2_000_000_000_000L;

    private final ApprovedRemoteScriptCatalog catalog = ApprovedRemoteScriptCatalog.standard(60_000L);

    @Test
    void standardCatalogContainsOneEnabledSafeDiagnosticAndBlockedFutureScripts() {
        assertEquals(Set.of("DIAG_HOSTNAME", "DIAG_IPCONFIG", "COLLECT_SUPPORT_BUNDLE"),
                catalog.definitions().stream()
                        .map(Definition::scriptId)
                        .collect(java.util.stream.Collectors.toSet()));
        Definition hostname = catalog.find("DIAG_HOSTNAME", "1").orElseThrow();
        assertTrue(hostname.enabled());
        assertFalse(hostname.revoked());
        assertEquals(RemoteSessionCapability.CONSTRAINED_PTY, hostname.requiredCapability());
        assertEquals(Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                hostname.approvalRequirements());
        assertEquals(RemoteOperationCatalog.RedactionClass.STANDARD_OUTPUT, hostname.redactionClass());
    }

    @Test
    void enabledScriptResolvesToServerOwnedConstrainedCommandByIdVersionAndHash() {
        Definition hostname = catalog.find("diag_hostname", "1").orElseThrow();
        var resolution = catalog.resolve(TENANT, invocation(hostname), NOW);

        assertTrue(resolution.allowed());
        assertEquals(ResolutionStatus.ALLOWED, resolution.status());
        assertEquals("hostname", resolution.prepared().commandLine());
        assertEquals(Map.of(), resolution.prepared().args());
    }

    @Test
    void rawScriptTextWrongHashWrongVersionAndUnknownIdsFailClosed() {
        Definition hostname = catalog.find("DIAG_HOSTNAME", "1").orElseThrow();

        assertEquals(ResolutionStatus.RAW_SCRIPT_TEXT_DENIED,
                catalog.resolve(TENANT, new Invocation(hostname.scriptId(), hostname.version(),
                        hostname.scriptBodySha256(), Map.of(), "hostname", null, null, null), NOW).status());
        assertEquals(ResolutionStatus.HASH_MISMATCH,
                catalog.resolve(TENANT, new Invocation(hostname.scriptId(), hostname.version(),
                        "0".repeat(64), Map.of(), null, null, null, null), NOW).status());
        assertEquals(ResolutionStatus.VERSION_MISMATCH,
                catalog.resolve(TENANT, new Invocation(hostname.scriptId(), "2",
                        hostname.scriptBodySha256(), Map.of(), null, null, null, null), NOW).status());
        assertEquals(ResolutionStatus.UNKNOWN,
                catalog.resolve(TENANT, new Invocation("NO_SUCH_SCRIPT", "1",
                        hostname.scriptBodySha256(), Map.of(), null, null, null, null), NOW).status());
    }

    @Test
    void disabledRevokedTenantDeniedExpiredAndBadArgsFailClosed() {
        Definition hostname = catalog.find("DIAG_HOSTNAME", "1").orElseThrow();
        Definition disabled = catalog.find("DIAG_IPCONFIG", "1").orElseThrow();
        Definition revoked = catalog.find("COLLECT_SUPPORT_BUNDLE", "1").orElseThrow();

        assertEquals(ResolutionStatus.DISABLED,
                catalog.resolve(TENANT, invocation(disabled), NOW).status());
        assertEquals(ResolutionStatus.REVOKED,
                catalog.resolve(TENANT, invocation(revoked), NOW).status());
        assertEquals(ResolutionStatus.ARG_SCHEMA_INVALID,
                catalog.resolve(TENANT, new Invocation(hostname.scriptId(), hostname.version(),
                        hostname.scriptBodySha256(), Map.of("extra", "value"), null, null, null, null), NOW).status());

        ApprovedRemoteScriptCatalog tenantScoped = new ApprovedRemoteScriptCatalog(Set.of(
                definition("DIAG_TENANT_HOSTNAME", "hostname\n", "hostname", Set.of(TENANT), List.of(),
                        RemoteOperationCatalog.RiskLevel.LOW,
                        Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                        NOW - 1_000L, NOW + 60_000L)));
        Definition scoped = tenantScoped.find("DIAG_TENANT_HOSTNAME", "1").orElseThrow();
        assertEquals(ResolutionStatus.TENANT_DENIED,
                tenantScoped.resolve(OTHER_TENANT, invocation(scoped), NOW).status());

        ApprovedRemoteScriptCatalog expiredCatalog = new ApprovedRemoteScriptCatalog(Set.of(
                definition("DIAG_EXPIRED", "hostname\n", "hostname", Set.of("*"), List.of(),
                        RemoteOperationCatalog.RiskLevel.LOW,
                        Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                        NOW - 10_000L, NOW - 1L)));
        Definition expired = expiredCatalog.find("DIAG_EXPIRED", "1").orElseThrow();
        assertEquals(ResolutionStatus.APPROVAL_EXPIRED,
                expiredCatalog.resolve(TENANT, invocation(expired), NOW).status());
    }

    @Test
    void secretArgsAndPolicyDeniedCommandsFailClosed() {
        ApprovedRemoteScriptCatalog secretArgCatalog = new ApprovedRemoteScriptCatalog(Set.of(
                definition("DIAG_NOTE", "hostname\n", "hostname", Set.of("*"),
                        List.of(new ArgumentSpec("note", ArgumentType.STRING, true, null, Set.of(), 128)),
                        RemoteOperationCatalog.RiskLevel.LOW,
                        Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                        NOW - 1_000L, NOW + 60_000L)));
        Definition note = secretArgCatalog.find("DIAG_NOTE", "1").orElseThrow();
        assertEquals(ResolutionStatus.ARG_SECRET_MATERIAL,
                secretArgCatalog.resolve(TENANT, new Invocation(note.scriptId(), note.version(), note.scriptBodySha256(),
                        Map.of("note", "eyJaaaaaa.eyJbbbbbb.cccccc"), null, null, null, null), NOW).status());

        ApprovedRemoteScriptCatalog deniedCommandCatalog = new ApprovedRemoteScriptCatalog(Set.of(
                definition("DIAG_BAD_COMMAND", "powershell\n", "powershell", Set.of("*"), List.of(),
                        RemoteOperationCatalog.RiskLevel.LOW,
                        Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                        NOW - 1_000L, NOW + 60_000L)));
        Definition denied = deniedCommandCatalog.find("DIAG_BAD_COMMAND", "1").orElseThrow();
        assertEquals(ResolutionStatus.COMMAND_POLICY_DENIED,
                deniedCommandCatalog.resolve(TENANT, invocation(denied), NOW).status());
    }

    @Test
    void constructionRejectsMutableUnsafeOrUnderApprovedDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovedRemoteScriptCatalog(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> definition("bad-id", "hostname\n", "hostname",
                Set.of("*"), List.of(), RemoteOperationCatalog.RiskLevel.LOW,
                Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP), NOW - 1_000L, NOW + 60_000L));
        assertThrows(IllegalArgumentException.class, () -> new Definition("DIAG_BAD_HASH", "1", "Bad hash",
                "hostname\n", "0".repeat(64), "release", "security", "APPROVAL-1",
                NOW - 1_000L, NOW + 60_000L, Set.of("*"), RemoteOperationCatalog.RiskLevel.LOW,
                Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                RemoteSessionCapability.CONSTRAINED_PTY, 60_000L,
                RemoteOperationCatalog.OutputRetention.WORM_TRANSCRIPT,
                RemoteOperationCatalog.RedactionClass.STANDARD_OUTPUT,
                "No cleanup.", "hostname", List.of(), true, false, null));
        assertThrows(IllegalArgumentException.class, () -> new Definition("DIAG_SELF_APPROVED", "1", "Self approved",
                "hostname\n", sha256("hostname\n"), "release", "release", "APPROVAL-1",
                NOW - 1_000L, NOW + 60_000L, Set.of("*"), RemoteOperationCatalog.RiskLevel.LOW,
                Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                RemoteSessionCapability.CONSTRAINED_PTY, 60_000L,
                RemoteOperationCatalog.OutputRetention.WORM_TRANSCRIPT,
                RemoteOperationCatalog.RedactionClass.STANDARD_OUTPUT,
                "No cleanup.", "hostname", List.of(), true, false, null));
        assertThrows(IllegalArgumentException.class, () -> definition("DIAG_HIGH", "hostname\n", "hostname",
                Set.of("*"), List.of(), RemoteOperationCatalog.RiskLevel.HIGH,
                Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP), NOW - 1_000L, NOW + 60_000L));
        assertThrows(IllegalArgumentException.class, () -> definition("DIAG_SECRET_BODY",
                "Authorization: Bearer abcdefgh\n", "hostname", Set.of("*"), List.of(),
                RemoteOperationCatalog.RiskLevel.LOW,
                Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                NOW - 1_000L, NOW + 60_000L));
    }

    private static Invocation invocation(Definition definition) {
        return new Invocation(definition.scriptId(), definition.version(), definition.scriptBodySha256(),
                Map.of(), null, null, null, null);
    }

    private static Definition definition(String id,
                                         String body,
                                         String commandTemplate,
                                         Set<String> tenantIds,
                                         List<ArgumentSpec> argsSchema,
                                         RemoteOperationCatalog.RiskLevel riskLevel,
                                         Set<RemoteOperationCatalog.ApprovalRequirement> approvalRequirements,
                                         long approvedAt,
                                         long approvalExpiresAt) {
        return new Definition(id, "1", id, body, sha256(body), "release", "security-board", "APPROVAL-" + id,
                approvedAt, approvalExpiresAt, tenantIds, riskLevel, approvalRequirements,
                RemoteSessionCapability.CONSTRAINED_PTY, 60_000L,
                RemoteOperationCatalog.OutputRetention.WORM_TRANSCRIPT,
                RemoteOperationCatalog.RedactionClass.STANDARD_OUTPUT,
                "No cleanup.", commandTemplate, argsSchema, true, false, null);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
