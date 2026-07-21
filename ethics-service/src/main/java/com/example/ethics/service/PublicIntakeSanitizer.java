package com.example.ethics.service;

import com.example.ethics.api.EthicsDtos.CreateReportRequest;
import com.example.ethics.api.EthicsDtos.MessageRequest;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Defense-in-depth input filter for public reporter-authored payloads.
 * The React shell auto-escapes on render, but the backend must refuse
 * markup so a downstream renderer (email, PDF export, WORM archive)
 * cannot re-introduce script execution and so audit logs record clean
 * text. URL patterns that only serve SSRF pivots are rejected as well.
 */
@Component
public class PublicIntakeSanitizer {

    // Any HTML/XML tag start: '<' + optional space + ASCII letter.
    // Prose like "x < 5" or "a <= b" passes because no letter follows.
    private static final Pattern HTML_TAG_START = Pattern.compile("<\\s*[a-zA-Z]");
    // Percent- and entity-encoded '<letter' payloads used to bypass naive filters.
    private static final Pattern ENCODED_TAG_START = Pattern.compile(
            "(?i)(?:&(?:#0*60|#x0*3c|lt);|%3c)\\s*[a-zA-Z]");

    // Schemes that are almost always attack surface in a whistleblower
    // narrative regardless of host: local file access, protocol smuggling,
    // and log4j-style lookups. Blocked outright.
    private static final Pattern BLOCKED_SCHEME = Pattern.compile(
            "(?i)\\b(?:file|gopher|jndi|dict|ldap|tftp|jar|zip|dns)://");

    // http/https/ftp are legitimate when pointing at an external service, so
    // they are blocked only when the host resolves inside the trust boundary
    // (loopback, private nets, link-local, cloud metadata).
    private static final Pattern BLOCKED_INTERNAL_HOST = Pattern.compile(
            "(?i)\\b(?:https?|ftp)://"
                    + "(?:"
                    + "127(?:\\.\\d{1,3}){3}"
                    + "|10(?:\\.\\d{1,3}){3}"
                    + "|192\\.168(?:\\.\\d{1,3}){2}"
                    + "|172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}"
                    + "|169\\.254(?:\\.\\d{1,3}){2}"
                    + "|0(?:\\.0){3}"
                    + "|localhost"
                    + "|\\[?::1\\]?"
                    + "|\\[?fe80:[0-9a-f:]*\\]?"
                    + "|metadata\\.google\\.internal"
                    + "|metadata\\.azure\\.internal"
                    + "|instance-data"
                    + ")");

    public void validateReport(CreateReportRequest request) {
        rejectHtmlTag(request.subject());
        rejectHtmlTag(request.description());
        rejectBlockedUrl(request.subject());
        rejectBlockedUrl(request.description());
    }

    public void validateMessage(MessageRequest request) {
        rejectHtmlTag(request.body());
        rejectBlockedUrl(request.body());
    }

    private static void rejectHtmlTag(String value) {
        if (value == null || value.isEmpty()) return;
        if (HTML_TAG_START.matcher(value).find() || ENCODED_TAG_START.matcher(value).find()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INPUT_HTML_NOT_ALLOWED");
        }
    }

    private static void rejectBlockedUrl(String value) {
        if (value == null || value.isEmpty()) return;
        if (BLOCKED_SCHEME.matcher(value).find() || BLOCKED_INTERNAL_HOST.matcher(value).find()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INPUT_URL_BLOCKED");
        }
    }
}
