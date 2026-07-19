package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ViewOnlyLeaseRedemptionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final String D1 = "sha256:" + "1".repeat(64);
    private static final String D2 = "sha256:" + "2".repeat(64);
    private static final String D3 = "sha256:" + "3".repeat(64);
    private static final String D4 = "sha256:" + "4".repeat(64);
    private static final String D5 = "sha256:" + "5".repeat(64);
    private static final String D6 = "sha256:" + "6".repeat(64);

    private JdbcViewOnlyCheckpointCas cas;
    private ViewOnlyLivePreflightRevalidator revalidator;
    private ViewOnlyLeaseReceiptSigner signer;
    private RemoteViewJsonCanonicalizer canonicalizer;
    private VerifiedViewOnlyLeaseRedeem verified;

    @BeforeEach
    void setUp() {
        cas = mock(JdbcViewOnlyCheckpointCas.class);
        revalidator = mock(ViewOnlyLivePreflightRevalidator.class);
        signer = mock(ViewOnlyLeaseReceiptSigner.class);
        canonicalizer = new RemoteViewJsonCanonicalizer();
        ObjectNode binding = canonicalizer.mapper().createObjectNode().put("headSha", "0".repeat(40));
        VerifiedViewOnlyPreflightReceipt evaluation = new VerifiedViewOnlyPreflightReceipt(
                D1, D2, D3, NOW.minusSeconds(600), NOW.minusSeconds(300), 0, false);
        VerifiedViewOnlyAuthorization authorization = new VerifiedViewOnlyAuthorization(
                D4, VerifiedViewOnlyAuthorization.PAYLOAD_TYPE, D2, D3,
                NOW.minusSeconds(60), NOW.plusSeconds(1800), false);
        ViewOnlyLeaseRedeemCommand command = new ViewOnlyLeaseRedeemCommand(
                UUID.fromString("123e4567-e89b-42d3-a456-426614174002"), D5, D6, D1,
                binding, D2, D3, evaluation, authorization, 900, 64);
        ViewOnlyOidcCaller caller = new ViewOnlyOidcCaller(
                "authorization", "https://token.actions.githubusercontent.com",
                "repo:Halildeu/platform-k8s-gitops:environment:faz22-view-only-pilot",
                1, 1, 1, 1, "refs/tags/cross-ai-intent/123e4567-e89b-42d3-a456-426614174000",
                "0".repeat(40), D6);
        verified = new VerifiedViewOnlyLeaseRedeem(command, caller);
    }

    @Test
    void exactRetrySkipsLiveChecksAndSigner() {
        byte[] stored = "stored".getBytes(StandardCharsets.UTF_8);
        when(cas.findLeaseRetry(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(stored));

        assertThat(service().redeem(verified)).isEqualTo(stored);
        verify(revalidator, never()).revalidate(any(), any());
        verify(signer, never()).sign(any());
    }

    @Test
    void newRedemptionRevalidatesSignsAndAtomicallyRegisters() {
        when(cas.findLeaseRetry(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        VerifiedViewOnlyPreflightReceipt refreshed = new VerifiedViewOnlyPreflightReceipt(
                D6, D2, D3, NOW.minusSeconds(5), NOW.plusSeconds(295), 0, false);
        when(revalidator.revalidate(any(), any())).thenReturn(refreshed);
        byte[] signed = "{\"payloadType\":\"lease\"}".getBytes(StandardCharsets.UTF_8);
        when(signer.sign(any())).thenReturn(signed);
        when(cas.registerLease(any())).thenReturn(signed);

        assertThat(service().redeem(verified)).isEqualTo(signed);
        verify(revalidator).revalidate(verified.command().binding(), verified.caller());
        verify(signer).sign(any(ViewOnlyLeaseSigningInput.class));
        verify(cas).registerLease(any(ViewOnlyLeaseRecord.class));
    }

    private ViewOnlyLeaseRedemptionService service() {
        return new ViewOnlyLeaseRedemptionService(
                cas, revalidator, signer, canonicalizer, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
