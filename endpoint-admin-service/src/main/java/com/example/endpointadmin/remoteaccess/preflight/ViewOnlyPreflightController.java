package com.example.endpointadmin.remoteaccess.preflight;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** GitHub-OIDC-only fixed-function authority routes. */
@RestController
@Profile("!local & !dev")
@RequestMapping("/api/v1/endpoint-admin/remote-access/preflight")
@ConditionalOnProperty(prefix = "endpoint-admin.view-only-authority", name = "enabled", havingValue = "true")
public final class ViewOnlyPreflightController {
    private final ViewOnlyLivePreflightService preflight;
    private final ViewOnlyLeaseRedeemVerifier leaseVerifier;
    private final ViewOnlyLeaseRedemptionService leaseService;
    private final ViewOnlyCheckpointCreateVerifier checkpointVerifier;
    private final JdbcViewOnlyCheckpointCas checkpointCas;
    private final ViewOnlyCheckpointReceiptSigner checkpointSigner;
    private final ViewOnlyOidcCallerFactory callerFactory;

    public ViewOnlyPreflightController(ViewOnlyLivePreflightService preflight,
                                       ViewOnlyLeaseRedeemVerifier leaseVerifier,
                                       ViewOnlyLeaseRedemptionService leaseService,
                                       ViewOnlyCheckpointCreateVerifier checkpointVerifier,
                                       JdbcViewOnlyCheckpointCas checkpointCas,
                                       ViewOnlyCheckpointReceiptSigner checkpointSigner,
                                       ViewOnlyOidcCallerFactory callerFactory) {
        this.preflight = preflight;
        this.leaseVerifier = leaseVerifier;
        this.leaseService = leaseService;
        this.checkpointVerifier = checkpointVerifier;
        this.checkpointCas = checkpointCas;
        this.checkpointSigner = checkpointSigner;
        this.callerFactory = callerFactory;
    }

    @PostMapping(value = "/attest", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> attest(@RequestBody byte[] body, @AuthenticationPrincipal Jwt jwt) {
        return signed(preflight.attest(body, jwt));
    }

    @PostMapping(value = "/checkpoint-leases/redeem", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> redeemLease(@RequestBody byte[] body, @AuthenticationPrincipal Jwt jwt) {
        Optional<byte[]> committed = leaseService.recoverCommitted(leaseVerifier.retryCandidate(body, jwt));
        if (committed.isPresent()) {
            return signed(committed.get());
        }
        return signed(leaseService.redeem(leaseVerifier.verify(body, jwt)));
    }

    @PostMapping(value = "/checkpoints", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> createCheckpoint(@RequestBody byte[] body, @AuthenticationPrincipal Jwt jwt) {
        String transactionId = checkpointVerifier.transactionIdForRetry(body);
        Optional<ViewOnlyOidcBinding> durableBinding = checkpointCas.findOidcBindingForRetry(transactionId);
        if (durableBinding.isPresent()) {
            Optional<byte[]> committed = checkpointCas.findCheckpointRetry(
                    checkpointVerifier.retryCandidate(body, jwt, durableBinding.get()));
            if (committed.isPresent()) {
                return signed(committed.get());
            }
        }
        VerifiedViewOnlyCheckpointCreate verified = checkpointVerifier.verify(body, jwt);
        return signed(checkpointCas.createCheckpoint(
                verified.command(), verified.caller(), checkpointSigner));
    }

    @GetMapping(value = "/checkpoints/{transactionIdSha256}/{sequence}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> readCheckpoint(
            @PathVariable String transactionIdSha256,
            @PathVariable int sequence,
            @AuthenticationPrincipal Jwt jwt) {
        ViewOnlyOidcCaller caller = callerFactory.create(
                jwt, ViewOnlyGithubOidcProfile.EXECUTOR,
                checkpointCas.readOidcBinding(transactionIdSha256));
        return signed(checkpointCas.readCheckpoint(transactionIdSha256, sequence, caller));
    }

    private static ResponseEntity<byte[]> signed(byte[] envelope) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(envelope);
    }
}
