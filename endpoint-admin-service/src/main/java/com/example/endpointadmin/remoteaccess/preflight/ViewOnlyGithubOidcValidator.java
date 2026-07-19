package com.example.endpointadmin.remoteaccess.preflight;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Static and internally-bound GitHub Actions OIDC claim validator. */
public final class ViewOnlyGithubOidcValidator implements OAuth2TokenValidator<Jwt> {
    static final String REPOSITORY = "Halildeu/platform-k8s-gitops";
    static final String REPOSITORY_ID = "1211415632";
    static final String OWNER = "Halildeu";
    static final String OWNER_ID = "186576227";
    static final String WORKFLOW_PATH = ".github/workflows/faz22-6-view-only-viewer-transaction.yml";
    static final String PROTECTED_ENVIRONMENT = "faz22-view-only-pilot";

    private static final String ISSUER = "https://token.actions.githubusercontent.com";
    private static final Pattern INTENT_REF = Pattern.compile(
            "refs/tags/cross-ai-intent/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern GIT_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Set<String> ALWAYS_FORBIDDEN = Set.of(
            "head_ref", "base_ref", "job_workflow_ref", "job_workflow_sha");

    private final ViewOnlyGithubOidcProfile profile;
    private final Clock clock;
    private final Duration maximumSkew;
    private final String trustedWorkflowCommitSha;

    public ViewOnlyGithubOidcValidator(ViewOnlyGithubOidcProfile profile,
                                       Clock clock,
                                       Duration maximumSkew,
                                       String trustedWorkflowCommitSha) {
        this.profile = profile;
        this.clock = clock;
        this.maximumSkew = maximumSkew;
        if (trustedWorkflowCommitSha == null || !GIT_SHA.matcher(trustedWorkflowCommitSha).matches()) {
            throw new IllegalArgumentException("trusted workflow commit SHA is required");
        }
        this.trustedWorkflowCommitSha = trustedWorkflowCommitSha;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            validateRequiredClaims(token);
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException invalid) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "GitHub OIDC authority claim set denied", null));
        }
    }

    private void validateRequiredClaims(Jwt token) {
        require(ISSUER.equals(token.getIssuer() == null ? null : token.getIssuer().toString()));
        require(token.getAudience().equals(List.of(profile.audience())));
        require(REPOSITORY.equals(text(token, "repository")));
        require(REPOSITORY_ID.equals(text(token, "repository_id")));
        require(OWNER.equals(text(token, "repository_owner")));
        require(OWNER_ID.equals(text(token, "repository_owner_id")));
        require("public".equals(text(token, "repository_visibility")));
        require("workflow_dispatch".equals(text(token, "event_name")));
        require("tag".equals(text(token, "ref_type")));
        require(profile.runnerEnvironment().equals(text(token, "runner_environment")));
        require("1".equals(text(token, "run_attempt")));
        requirePositiveDecimal(text(token, "actor_id"));
        requirePositiveDecimal(text(token, "run_id"));
        require(text(token, "jti") != null && !text(token, "jti").isBlank());

        String ref = text(token, "ref");
        String sha = text(token, "sha");
        require(INTENT_REF.matcher(ref).matches());
        require(GIT_SHA.matcher(sha).matches());
        require(trustedWorkflowCommitSha.equals(sha));
        require(sha.equals(text(token, "workflow_sha")));
        require((REPOSITORY + "/" + WORKFLOW_PATH + "@" + ref).equals(text(token, "workflow_ref")));

        Map<String, Object> claims = token.getClaims();
        ALWAYS_FORBIDDEN.forEach(claim -> require(!claims.containsKey(claim)));
        if (profile.protectedEnvironment()) {
            require(PROTECTED_ENVIRONMENT.equals(text(token, "environment")));
            require(("repo:" + REPOSITORY + ":environment:" + PROTECTED_ENVIRONMENT)
                    .equals(token.getSubject()));
        } else {
            require(!claims.containsKey("environment"));
            require(("repo:" + REPOSITORY + ":ref:" + ref).equals(token.getSubject()));
        }

        Instant issuedAt = token.getIssuedAt();
        Instant notBefore = token.getNotBefore();
        Instant expiresAt = token.getExpiresAt();
        require(issuedAt != null && notBefore != null && expiresAt != null);
        require(!expiresAt.isBefore(issuedAt));
        require(Duration.between(issuedAt, expiresAt).compareTo(Duration.ofSeconds(300)) <= 0);
        Instant now = clock.instant();
        require(!issuedAt.isAfter(now.plus(maximumSkew)));
        require(!notBefore.isAfter(now.plus(maximumSkew)));
        require(expiresAt.isAfter(now.minus(maximumSkew)));
    }

    private static String text(Jwt token, String claim) {
        Object value = token.getClaims().get(claim);
        return value == null ? null : value.toString();
    }

    private static void requirePositiveDecimal(String value) {
        require(value != null && value.matches("[1-9][0-9]*"));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("OIDC claim denied");
        }
    }
}
