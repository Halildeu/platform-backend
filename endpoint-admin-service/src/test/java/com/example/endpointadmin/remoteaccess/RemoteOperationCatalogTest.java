package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.RemoteOperationCatalog.ResolutionStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteOperationCatalogTest {

    private final RemoteOperationCatalog catalog = RemoteOperationCatalog.standard(60_000L);

    @Test
    void standardCatalogContainsTheInitialRemoteResponseOperations() {
        assertEquals(Set.of(
                        "GET_AGENT_STATUS",
                        "GET_AGENT_VERSION",
                        "GET_HOSTNAME",
                        "GET_NETWORK_SUMMARY",
                        "GET_SERVICE_STATUS",
                        "COLLECT_AGENT_LOGS",
                        "RUN_CERT_AUTOENROLL_PULSE",
                        "REFRESH_SOFTWARE_INVENTORY"),
                catalog.entries().stream().map(RemoteOperationCatalog.Entry::id).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void enabledOperationsResolveToServerOwnedPtyCommands() {
        var hostname = catalog.resolve("GET_HOSTNAME", null, null);
        assertTrue(hostname.allowed());
        assertEquals(RemoteOperation.PTY_COMMAND, hostname.entry().operation());
        assertEquals("hostname", hostname.entry().commandLine());
        assertEquals(RemoteSessionCapability.CONSTRAINED_PTY, hostname.entry().requiredCapability());
        assertEquals(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP,
                hostname.entry().approvalRequirement());
        assertTrue(hostname.entry().consentRequired());

        var network = catalog.resolve("get_network_summary", RemoteOperation.PTY_COMMAND, null);
        assertTrue(network.allowed());
        assertEquals("netstat -a -n", network.entry().commandLine());
    }

    @Test
    void unknownDisabledMismatchedOrOverriddenOperationsFailClosed() {
        assertEquals(ResolutionStatus.UNKNOWN,
                catalog.resolve("NO_SUCH_OPERATION", null, null).status());
        assertEquals(ResolutionStatus.DISABLED,
                catalog.resolve("GET_SERVICE_STATUS", null, null).status());
        assertFalse(catalog.resolve("GET_SERVICE_STATUS", null, null).allowed());
        assertEquals(ResolutionStatus.OPERATION_MISMATCH,
                catalog.resolve("GET_HOSTNAME", RemoteOperation.SCREEN_VIEW, null).status());
        assertEquals(ResolutionStatus.OVERRIDE_ATTEMPT,
                catalog.resolve("GET_HOSTNAME", RemoteOperation.PTY_COMMAND, "hostname").status());
        assertEquals(ResolutionStatus.OVERRIDE_ATTEMPT,
                catalog.resolve("GET_HOSTNAME", RemoteOperation.PTY_COMMAND, "powershell -EncodedCommand x").status());
    }

    @Test
    void registryConstructionRejectsUnsafeEntries() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteOperationCatalog(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new RemoteOperationCatalog(Set.of(
                new RemoteOperationCatalog.Entry("bad-id", "Bad", RemoteOperation.PTY_COMMAND, "hostname",
                        RemoteSessionCapability.CONSTRAINED_PTY, RemoteOperationCatalog.RiskLevel.LOW,
                        RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP, true, 60_000L,
                        RemoteOperationCatalog.OutputRetention.WORM_TRANSCRIPT,
                        RemoteOperationCatalog.RedactionClass.STANDARD_OUTPUT, true, null))));
        assertThrows(IllegalArgumentException.class, () -> new RemoteOperationCatalog(Set.of(
                new RemoteOperationCatalog.Entry("BAD_PTY", "Bad", RemoteOperation.PTY_COMMAND, "",
                        RemoteSessionCapability.CONSTRAINED_PTY, RemoteOperationCatalog.RiskLevel.LOW,
                        RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP, true, 60_000L,
                        RemoteOperationCatalog.OutputRetention.WORM_TRANSCRIPT,
                        RemoteOperationCatalog.RedactionClass.STANDARD_OUTPUT, true, null))));
    }
}
