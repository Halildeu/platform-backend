package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact signed binding fields that a verified GitHub OIDC token must equal. */
public record ViewOnlyOidcBinding(
        long triggeringActorId,
        long runId,
        int runAttempt,
        String intentRef,
        String headSha) {

    private static final Set<String> EXACT_FIELDS = Set.of(
            "triggeringActorId", "runId", "runAttempt", "intentRef", "headSha");
    private static final Pattern INTENT_REF = Pattern.compile(
            "refs/tags/cross-ai-intent/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    public ViewOnlyOidcBinding {
        if (triggeringActorId < 1 || runId < 1 || runAttempt != 1
                || intentRef == null || !INTENT_REF.matcher(intentRef).matches()
                || headSha == null || !headSha.matches("[0-9a-f]{40}")) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID, "OIDC binding is invalid");
        }
    }

    public static ViewOnlyOidcBinding fromJson(JsonNode binding) {
        if (binding == null || !binding.isObject()) {
            throw invalid();
        }
        Set<String> fields = new HashSet<>();
        Iterator<String> names = binding.fieldNames();
        names.forEachRemaining(fields::add);
        if (!fields.equals(EXACT_FIELDS)) {
            throw invalid();
        }
        JsonNode actor = binding.get("triggeringActorId");
        JsonNode run = binding.get("runId");
        JsonNode attempt = binding.get("runAttempt");
        JsonNode intentRef = binding.get("intentRef");
        JsonNode headSha = binding.get("headSha");
        if (actor == null || !actor.isIntegralNumber() || !actor.canConvertToLong()
                || run == null || !run.isIntegralNumber() || !run.canConvertToLong()
                || attempt == null || !attempt.isIntegralNumber() || !attempt.canConvertToInt()
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
