package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import java.time.Clock;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Default-off core wiring. Enabling requires real DSSE verifiers, fixed live
 * probes and Vault Transit signers; absence of any dependency prevents startup
 * instead of substituting an in-memory, unsigned or product-credential path.
 */
@Configuration
@EnableConfigurationProperties(ViewOnlyAuthorityProperties.class)
@ConditionalOnProperty(prefix = "endpoint-admin.view-only-authority", name = "enabled", havingValue = "true")
public class ViewOnlyAuthorityCoreConfig {

    @Bean("viewOnlyJsonCanonicalizer")
    public RemoteViewJsonCanonicalizer viewOnlyJsonCanonicalizer(ViewOnlyAuthorityProperties properties) {
        properties.validateActivation();
        return new RemoteViewJsonCanonicalizer();
    }

    @Bean
    public ViewOnlyOidcCallerFactory viewOnlyOidcCallerFactory(
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer,
            ViewOnlyAuthorityProperties properties) {
        return new ViewOnlyOidcCallerFactory(canonicalizer, properties.getJtiDigestDomain());
    }

    @Bean
    public ViewOnlyPublicTrustStore viewOnlyPublicTrustStore(
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer,
            Clock clock,
            ViewOnlyAuthorityProperties properties) {
        ViewOnlyPublicTrustStore trust = new ViewOnlyPublicTrustStore(canonicalizer, clock, properties);
        trust.probeReady();
        return trust;
    }

    @Bean
    public StrictViewOnlyBindingHandoffVerifier strictViewOnlyBindingHandoffVerifier(
            ViewOnlyPublicTrustStore trust,
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer) {
        return new StrictViewOnlyBindingHandoffVerifier(trust, canonicalizer);
    }

    @Bean
    public ViewOnlyPreflightEnvelopeVerifier viewOnlyPreflightEnvelopeVerifier(
            ViewOnlyPublicTrustStore trust,
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer) {
        return new StrictViewOnlyPreflightEnvelopeVerifier(trust, canonicalizer);
    }

    @Bean
    public ViewOnlyAuthorizationEnvelopeVerifier viewOnlyAuthorizationEnvelopeVerifier(
            ViewOnlyPublicTrustStore trust,
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer) {
        return new StrictViewOnlyAuthorizationEnvelopeVerifier(trust, canonicalizer);
    }

    @Bean
    public ViewOnlyLeaseEnvelopeVerifier viewOnlyLeaseEnvelopeVerifier(
            ViewOnlyPublicTrustStore trust,
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer) {
        return new StrictViewOnlyLeaseEnvelopeVerifier(trust, canonicalizer);
    }

    @Bean
    public FixedProcessViewOnlyLivePreflight fixedProcessViewOnlyLivePreflight(
            ViewOnlyAuthorityProperties properties,
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer,
            StrictViewOnlyBindingHandoffVerifier bindingVerifier,
            ViewOnlyPreflightEnvelopeVerifier preflightEnvelopeVerifier,
            ViewOnlyOidcCallerFactory callerFactory,
            Clock clock) {
        if (!(preflightEnvelopeVerifier instanceof StrictViewOnlyPreflightEnvelopeVerifier strictVerifier)) {
            throw new IllegalStateException(
                    "VIEW_ONLY authority activation denied: concrete preflight verifier is required");
        }
        FixedProcessViewOnlyLivePreflight preflight = new FixedProcessViewOnlyLivePreflight(
                properties, canonicalizer, bindingVerifier, strictVerifier, callerFactory, clock);
        preflight.probeReady();
        return preflight;
    }

    @Bean
    public ViewOnlyVaultTokenSource viewOnlyVaultTokenSource(ViewOnlyAuthorityProperties properties) {
        return new FileViewOnlyVaultTokenSource(Path.of(properties.getVaultTokenFile()));
    }

    @Bean
    public ViewOnlyTransitSigningClient viewOnlyTransitSigningClient(
            ViewOnlyAuthorityProperties properties,
            ViewOnlyVaultTokenSource tokenSource) {
        ViewOnlyTransitSigningClient transit = new VaultTransitViewOnlySigningClient(properties, tokenSource);
        transit.probeReady();
        return transit;
    }

    @Bean
    public ViewOnlyCheckpointTransitReceiptSigner viewOnlyCheckpointTransitReceiptSigner(
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer,
            ViewOnlyTransitSigningClient transit,
            ViewOnlyAuthorityProperties properties) {
        ViewOnlyReceiptPayloadFactory payloads = new ViewOnlyReceiptPayloadFactory(canonicalizer);
        ViewOnlyDsseSigner dsse = new ViewOnlyDsseSigner(
                canonicalizer, transit, properties.getVaultTransitKeyId());
        return new ViewOnlyCheckpointTransitReceiptSigner(payloads, dsse);
    }

    @Bean
    public JdbcViewOnlyCheckpointCas jdbcViewOnlyCheckpointCas(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer,
            Clock clock,
            ViewOnlyAuthorityProperties properties) {
        JdbcViewOnlyCheckpointCas cas = new JdbcViewOnlyCheckpointCas(
                jdbc, transactionManager, canonicalizer, clock, properties.getSchema());
        cas.probeAvailable();
        return cas;
    }

    @Bean
    public ViewOnlyCheckpointCreateVerifier viewOnlyCheckpointCreateVerifier(
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer,
            ViewOnlyLeaseEnvelopeVerifier leaseEnvelopeVerifier,
            ViewOnlyOidcCallerFactory callerFactory,
            Clock clock,
            ViewOnlyAuthorityProperties properties) {
        return new ViewOnlyCheckpointCreateVerifier(
                canonicalizer, leaseEnvelopeVerifier, callerFactory, clock,
                properties.getCheckpointCreateIdempotencyDomain());
    }

    @Bean
    public ViewOnlyLeaseRedeemVerifier viewOnlyLeaseRedeemVerifier(
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer,
            ViewOnlyPreflightEnvelopeVerifier preflightEnvelopeVerifier,
            ViewOnlyAuthorizationEnvelopeVerifier authorizationEnvelopeVerifier,
            ViewOnlyOidcCallerFactory callerFactory,
            Clock clock) {
        return new ViewOnlyLeaseRedeemVerifier(
                canonicalizer, preflightEnvelopeVerifier, authorizationEnvelopeVerifier, callerFactory, clock);
    }

    @Bean
    public ViewOnlyLeaseRedemptionService viewOnlyLeaseRedemptionService(
            JdbcViewOnlyCheckpointCas cas,
            ViewOnlyLivePreflightRevalidator livePreflightRevalidator,
            ViewOnlyLeaseReceiptSigner leaseReceiptSigner,
            @Qualifier("viewOnlyJsonCanonicalizer") RemoteViewJsonCanonicalizer canonicalizer,
            Clock clock) {
        return new ViewOnlyLeaseRedemptionService(
                cas, livePreflightRevalidator, leaseReceiptSigner, canonicalizer, clock);
    }
}
