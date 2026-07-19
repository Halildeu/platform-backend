package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

/** Exact signed binding fields that a verified GitHub OIDC token must equal. */
public record ViewOnlyOidcBinding(
        long triggeringActorId,
        long runId,
        int runAttempt,
        String intentRef,
        String headSha) {

    public ViewOnlyOidcBinding {
        if (triggeringActorId < 1 || runId < 1 || runAttempt != 1
                || intentRef == null || intentRef.isBlank()
                || headSha == null || !headSha.matches("[0-9a-f]{40}")) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID, "OIDC binding is invalid");
        }
    }

    public static ViewOnlyOidcBinding fromJson(JsonNode binding) {
        if (binding == null || !binding.isObject()) {
            throw invalid();
        }
        JsonNode actor = binding.get("triggeringActorId");
        JsonNode run = binding.get("runId");
        JsonNode attempt = binding.get("runAttempt");
        JsonNode intentRef = binding.get("intentRef");
        JsonNode headSha = binding.get("headSha");
        if (actor == null || !actor.canConvertToLong() || run == null || !run.canConvertToLong()
                || attempt == null || !attempt.canConvertToInt()
                || intentRef == null || !intentRef.isTextual()
                || headSha == null || !headSha.isTextual()) {
            throw invalid();
        }
        return new ViewOnlyOidcBinding(
                actor.longValue(), run.longValue(), attempt.intValue(),
                intentRef.textValue(), headSha.textValue());
    }

    private static ViewOnlyAuthorityException invalid() {
        return new ViewOnlyAuthorityException(
                ViewOnlyAuthorityError.CONTRACT_INVALID, "OIDC binding is invalid");
    }
}
