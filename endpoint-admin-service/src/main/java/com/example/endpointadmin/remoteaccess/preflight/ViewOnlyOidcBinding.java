package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/** Exact signed binding fields that a verified GitHub OIDC token must equal. */
public record ViewOnlyOidcBinding(
        long repositoryId,
        long triggeringActorId,
        long runId,
        int runAttempt,
        String intentRef,
        String headSha) {

    private static final Pattern INTENT_REF = Pattern.compile(
            "refs/tags/cross-ai-intent/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    public ViewOnlyOidcBinding {
        if (repositoryId != ViewOnlyGithubOidcValidator.REPOSITORY_ID_NUMBER
                || triggeringActorId < 1 || runId < 1 || runAttempt != 1
                || intentRef == null || !INTENT_REF.matcher(intentRef).matches()
                || headSha == null || !headSha.matches("[0-9a-f]{40}")) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID, "OIDC binding is invalid");
        }
    }

    public static ViewOnlyOidcBinding fromJson(JsonNode binding) {
        ViewOnlyTransactionBinding.requireExact(binding);
        JsonNode actor = binding.get("triggeringActorId");
        JsonNode repository = binding.get("repositoryId");
        JsonNode run = binding.get("runId");
        JsonNode attempt = binding.get("runAttempt");
        JsonNode intentRef = binding.get("intentRef");
        JsonNode headSha = binding.get("headSha");
        if (repository == null || !repository.isIntegralNumber() || !repository.canConvertToLong()
                || actor == null || !actor.isIntegralNumber() || !actor.canConvertToLong()
                || run == null || !run.isIntegralNumber() || !run.canConvertToLong()
                || attempt == null || !attempt.isIntegralNumber() || !attempt.canConvertToInt()
                || intentRef == null || !intentRef.isTextual()
                || headSha == null || !headSha.isTextual()) {
            throw invalid();
        }
        return new ViewOnlyOidcBinding(
                repository.longValue(), actor.longValue(), run.longValue(), attempt.intValue(),
                intentRef.textValue(), headSha.textValue());
    }

    private static ViewOnlyAuthorityException invalid() {
        return new ViewOnlyAuthorityException(
                ViewOnlyAuthorityError.CONTRACT_INVALID, "OIDC binding is invalid");
    }
}
