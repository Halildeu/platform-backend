package com.example.ethics.intake;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ethics.api.EthicsDtos.ReportMode;
import com.example.ethics.model.OrgReportPolicy;
import com.example.ethics.repository.OrgReportPolicyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ES-212 — the tenant-parametric gate, including the direction it fails in. */
class ReportModePolicyTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void anOrgWithNoPolicyRowGetsAnonymousOnly() {
        OrgReportPolicyRepository repo = mock(OrgReportPolicyRepository.class);
        when(repo.findById(ORG)).thenReturn(Optional.empty());
        ReportModePolicy policy = new ReportModePolicy(repo);

        // This is what makes the migration safe to ship: every tenant already running has
        // no row, so nothing about what they collect changes.
        assertTrue(policy.isEnabled(ORG, ReportMode.ANONYMOUS));
        assertFalse(policy.isEnabled(ORG, ReportMode.CONFIDENTIAL));
        assertFalse(policy.isEnabled(ORG, ReportMode.NAMED));
    }

    @Test
    void enabledModesFollowTheRow() {
        OrgReportPolicyRepository repo = mock(OrgReportPolicyRepository.class);
        when(repo.findById(ORG)).thenReturn(Optional.of(
                new OrgReportPolicy(ORG, true, false, null, Instant.EPOCH, "owner")));
        ReportModePolicy policy = new ReportModePolicy(repo);

        assertTrue(policy.isEnabled(ORG, ReportMode.CONFIDENTIAL));
        assertFalse(policy.isEnabled(ORG, ReportMode.NAMED),
                "each mode is opted into separately; enabling one must not enable the other");
    }

    @Test
    void anUnreadableStoreFallsBackToTheAnonymousFloorRatherThanOpenOrShut() {
        OrgReportPolicyRepository repo = mock(OrgReportPolicyRepository.class);
        when(repo.findById(any())).thenThrow(new RuntimeException("database is down"));
        ReportModePolicy policy = new ReportModePolicy(repo);

        // Failing open would collect names with no lawful basis, and unlike a wrong access
        // decision that cannot be undone once the person has typed their name. Failing shut
        // would close the reporting channel over a database hiccup. The floor is the only
        // answer that is safe on both counts: reporting stays possible, identity collection
        // stops.
        assertTrue(policy.isEnabled(ORG, ReportMode.ANONYMOUS),
                "the reporting channel must never close because a policy row could not be read");
        assertFalse(policy.isEnabled(ORG, ReportMode.CONFIDENTIAL));
        assertFalse(policy.isEnabled(ORG, ReportMode.NAMED));
    }

    @Test
    void onlyAnonymousIsFreeOfIdentityCollection() {
        assertFalse(ReportModePolicy.collectsIdentity(ReportMode.ANONYMOUS));
        assertTrue(ReportModePolicy.collectsIdentity(ReportMode.CONFIDENTIAL));
        assertTrue(ReportModePolicy.collectsIdentity(ReportMode.NAMED));
    }
}
