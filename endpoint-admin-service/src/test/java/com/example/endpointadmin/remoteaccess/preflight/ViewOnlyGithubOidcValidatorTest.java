package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ViewOnlyGithubOidcValidatorTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REF = "refs/tags/cross-ai-intent/123e4567-e89b-42d3-a456-426614174000";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void acceptsExactPreflightClaimSet() {
        assertThat(validate(ViewOnlyGithubOidcProfile.PREFLIGHT, token(ViewOnlyGithubOidcProfile.PREFLIGHT)))
                .isTrue();
    }

    @Test
    void acceptsOnlyEnvironmentBoundAuthorizationProfile() {
        assertThat(validate(ViewOnlyGithubOidcProfile.AUTHORIZATION, token(ViewOnlyGithubOidcProfile.AUTHORIZATION)))
                .isTrue();
        Jwt unprotected = token(ViewOnlyGithubOidcProfile.PREFLIGHT);
        assertThat(validate(ViewOnlyGithubOidcProfile.AUTHORIZATION, unprotected)).isFalse();
    }

    @Test
    void rejectsEnvironmentLeakOnPreflight() {
        Jwt token = builder(ViewOnlyGithubOidcProfile.PREFLIGHT)
                .claim("environment", ViewOnlyGithubOidcValidator.PROTECTED_ENVIRONMENT)
                .build();
        assertThat(validate(ViewOnlyGithubOidcProfile.PREFLIGHT, token)).isFalse();
    }

    @Test
    void rejectsAdditionalAudienceAndWorkflowMismatch() {
        Jwt extraAudience = builder(ViewOnlyGithubOidcProfile.EXECUTOR)
                .audience(List.of(ViewOnlyGithubOidcProfile.EXECUTOR.audience(), "other"))
                .build();
        assertThat(validate(ViewOnlyGithubOidcProfile.EXECUTOR, extraAudience)).isFalse();

        Jwt wrongWorkflow = builder(ViewOnlyGithubOidcProfile.EXECUTOR)
                .claim("workflow_sha", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .build();
        assertThat(validate(ViewOnlyGithubOidcProfile.EXECUTOR, wrongWorkflow)).isFalse();

        Jwt untrustedCommit = builder(ViewOnlyGithubOidcProfile.EXECUTOR)
                .claim("sha", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .claim("workflow_sha", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .build();
        assertThat(validate(ViewOnlyGithubOidcProfile.EXECUTOR, untrustedCommit)).isFalse();
    }

    @Test
    void callerFactoryBindsExactRunAndProducesOnlyHashedJti() {
        RemoteViewJsonCanonicalizer canonicalizer = new RemoteViewJsonCanonicalizer();
        ViewOnlyOidcCallerFactory factory = new ViewOnlyOidcCallerFactory(
                canonicalizer, ViewOnlyAuthorityProperties.CANONICAL_OIDC_JTI_DIGEST_DOMAIN);
        ViewOnlyOidcCaller caller = factory.create(
                token(ViewOnlyGithubOidcProfile.EXECUTOR),
                ViewOnlyGithubOidcProfile.EXECUTOR,
                new ViewOnlyOidcBinding(1211415632L, 186576227L, 29678094664L, 1, REF, SHA));

        assertThat(caller.profile()).isEqualTo("executor");
        assertThat(caller.tokenJtiSha256()).matches("sha256:[0-9a-f]{64}");
        assertThat(caller.tokenJtiSha256()).doesNotContain("jti-raw");
        var exactProjection = canonicalizer.mapper().createObjectNode();
        exactProjection.put("domain", ViewOnlyAuthorityProperties.CANONICAL_OIDC_JTI_DIGEST_DOMAIN);
        exactProjection.put("issuer", "https://token.actions.githubusercontent.com");
        exactProjection.put("audience", ViewOnlyGithubOidcProfile.EXECUTOR.audience());
        exactProjection.put("subject", "repo:" + ViewOnlyGithubOidcValidator.REPOSITORY + ":ref:" + REF);
        exactProjection.put("jti", "jti-raw-never-persisted");
        assertThat(caller.tokenJtiSha256()).isEqualTo(canonicalizer.digest(exactProjection));
        assertThat(caller.receiptProjection(canonicalizer).toString())
                .doesNotContain("jti-raw-never-persisted");

        ViewOnlyOidcCaller otherDomainCaller = new ViewOnlyOidcCallerFactory(
                new RemoteViewJsonCanonicalizer(), "test-only-jti-domain/v2").create(
                token(ViewOnlyGithubOidcProfile.EXECUTOR),
                ViewOnlyGithubOidcProfile.EXECUTOR,
                new ViewOnlyOidcBinding(1211415632L, 186576227L, 29678094664L, 1, REF, SHA));
        assertThat(otherDomainCaller.tokenJtiSha256()).isNotEqualTo(caller.tokenJtiSha256());

        assertThatThrownBy(() -> factory.create(
                token(ViewOnlyGithubOidcProfile.EXECUTOR),
                ViewOnlyGithubOidcProfile.EXECUTOR,
                new ViewOnlyOidcBinding(1211415632L, 186576227L, 9L, 1, REF, SHA)))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.LEASE_BINDING_MISMATCH);

        ViewOnlyOidcCaller wrongProfileProjection = factory.create(
                token(ViewOnlyGithubOidcProfile.PREFLIGHT),
                ViewOnlyGithubOidcProfile.PREFLIGHT,
                new ViewOnlyOidcBinding(1211415632L, 186576227L, 29678094664L, 1, REF, SHA));
        assertThat(wrongProfileProjection.tokenJtiSha256()).isNotEqualTo(caller.tokenJtiSha256());
    }

    @Test
    void signedOidcBindingRejectsARepositoryIdOutsideThePinnedRepository() {
        assertThatThrownBy(() -> new ViewOnlyOidcBinding(
                999L, 186576227L, 29678094664L, 1, REF, SHA))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.CONTRACT_INVALID);
    }

    private static boolean validate(ViewOnlyGithubOidcProfile profile, Jwt token) {
        return !new ViewOnlyGithubOidcValidator(profile, CLOCK, Duration.ofSeconds(30), SHA)
                .validate(token).hasErrors();
    }

    private static Jwt token(ViewOnlyGithubOidcProfile profile) {
        return builder(profile).build();
    }

    private static Jwt.Builder builder(ViewOnlyGithubOidcProfile profile) {
        String subject = profile.protectedEnvironment()
                ? "repo:" + ViewOnlyGithubOidcValidator.REPOSITORY + ":environment:"
                        + ViewOnlyGithubOidcValidator.PROTECTED_ENVIRONMENT
                : "repo:" + ViewOnlyGithubOidcValidator.REPOSITORY + ":ref:" + REF;
        Jwt.Builder builder = Jwt.withTokenValue("redacted")
                .header("alg", "RS256")
                .issuer("https://token.actions.githubusercontent.com")
                .subject(subject)
                .audience(List.of(profile.audience()))
                .issuedAt(NOW.minusSeconds(20))
                .notBefore(NOW.minusSeconds(20))
                .expiresAt(NOW.plusSeconds(280))
                .claim("repository", ViewOnlyGithubOidcValidator.REPOSITORY)
                .claim("repository_id", ViewOnlyGithubOidcValidator.REPOSITORY_ID)
                .claim("repository_owner", ViewOnlyGithubOidcValidator.OWNER)
                .claim("repository_owner_id", ViewOnlyGithubOidcValidator.OWNER_ID)
                .claim("repository_visibility", "public")
                .claim("event_name", "workflow_dispatch")
                .claim("ref_type", "tag")
                .claim("runner_environment", profile.runnerEnvironment())
                .claim("actor_id", "186576227")
                .claim("run_id", "29678094664")
                .claim("run_attempt", "1")
                .claim("ref", REF)
                .claim("sha", SHA)
                .claim("workflow_sha", SHA)
                .claim("workflow_ref", ViewOnlyGithubOidcValidator.REPOSITORY + "/"
                        + ViewOnlyGithubOidcValidator.WORKFLOW_PATH + "@" + REF)
                .claim("jti", "jti-raw-never-persisted");
        if (profile.protectedEnvironment()) {
            builder.claim("environment", ViewOnlyGithubOidcValidator.PROTECTED_ENVIRONMENT);
        }
        return builder;
    }
}
