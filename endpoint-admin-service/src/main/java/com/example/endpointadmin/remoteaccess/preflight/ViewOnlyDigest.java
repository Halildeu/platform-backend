package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Pattern;

/** Domain-separated RFC 8785/JCS digest helper. */
public final class ViewOnlyDigest {
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private final RemoteViewJsonCanonicalizer canonicalizer;

    public ViewOnlyDigest(RemoteViewJsonCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    public String domainDigest(String domain, String valueName, JsonNode value) {
        if (domain == null || domain.isBlank() || valueName == null || valueName.isBlank() || value == null) {
            throw invalid("digest domain, value name and value are required");
        }
        ObjectNode projection = canonicalizer.mapper().createObjectNode();
        projection.put("domain", domain);
        projection.set(valueName, value);
        return canonicalizer.digest(projection);
    }

    public String bodyDigest(JsonNode validatedRequestWithoutIdempotencyKey) {
        if (validatedRequestWithoutIdempotencyKey == null) {
            throw invalid("validated request is required");
        }
        return canonicalizer.digest(validatedRequestWithoutIdempotencyKey);
    }

    public static String requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw invalid(field + " must be a lowercase sha256 digest");
        }
        return value;
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message);
    }
}
