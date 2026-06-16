package com.example.endpointadmin.domainops;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class DomainOpsCredentialRefPolicy {

    private static final int MAX_LENGTH = 256;
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "vault:",
            "os-credential:",
            "delegated-worker:",
            "secret-ref:");
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[;|&<>]");
    private static final Pattern UNSAFE = Pattern.compile(
            "(?i)(password\\s*=|passwd\\s*=|pwd\\s*=|bearer\\s+|secret\\s*=|-----BEGIN|private\\s+key)");

    private DomainOpsCredentialRefPolicy() {
    }

    public static String normalizeRequired(String raw) {
        if (raw == null) {
            throw missing();
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw missing();
        }
        if (value.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain ops credential ref is too long.");
        }
        if (value.chars().anyMatch(ch -> Character.isISOControl(ch) || Character.isWhitespace(ch))
                || UNSAFE_CHARS.matcher(value).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain ops credential ref contains unsupported characters.");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        boolean allowedPrefix = ALLOWED_PREFIXES.stream().anyMatch(lower::startsWith);
        if (!allowedPrefix || UNSAFE.matcher(value).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain ops credential ref must be an opaque external secret reference.");
        }
        return value;
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Domain ops credential ref is required.");
    }
}
