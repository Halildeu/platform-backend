package com.example.audiogateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict, bounded client-to-gateway live stream control frame. */
public record LiveStreamControlFrame(Type type, List<String> terms) {

    public static final String CANONICAL_EOF = "{\"type\":\"eof\"}";
    static final int MAX_CONTEXT_TERMS = 32;
    static final int MAX_CONTEXT_TERM_CHARS = 64;
    static final int MAX_CONTEXT_TOTAL_CHARS = 512;
    private static final Set<Integer> ALLOWED_PUNCTUATION = Set.of(
            (int) ' ', (int) '-', (int) '\'', (int) '.');

    public enum Type {
        EOF,
        CONTEXT
    }

    public static LiveStreamControlFrame decode(
            final String value,
            final int maxBytes,
            final ObjectMapper objectMapper) {
        if (value == null
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw invalid();
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(value);
        } catch (Exception ignored) {
            throw invalid();
        }
        if (root == null || !root.isObject() || !root.path("type").isTextual()) {
            throw invalid();
        }
        if ("eof".equals(root.path("type").textValue())) {
            if (root.size() != 1) {
                throw invalid();
            }
            return new LiveStreamControlFrame(Type.EOF, List.of());
        }
        if (!"context".equals(root.path("type").textValue())
                || root.size() != 2
                || !root.path("terms").isArray()
                || root.path("terms").size() > MAX_CONTEXT_TERMS) {
            throw invalid();
        }
        final List<String> terms = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        int totalChars = 0;
        for (JsonNode valueNode : root.path("terms")) {
            if (!valueNode.isTextual()) {
                throw invalid();
            }
            final String term = normalizeTerm(valueNode.textValue());
            final String dedupeKey = term.toLowerCase(Locale.ROOT);
            if (!seen.add(dedupeKey)) {
                continue;
            }
            totalChars += term.codePointCount(0, term.length());
            if (totalChars > MAX_CONTEXT_TOTAL_CHARS) {
                throw invalid();
            }
            terms.add(term);
        }
        return new LiveStreamControlFrame(Type.CONTEXT, List.copyOf(terms));
    }

    public boolean terminal() {
        return type == Type.EOF;
    }

    public String upstreamPayload(final ObjectMapper objectMapper) {
        if (terminal()) {
            return CANONICAL_EOF;
        }
        try {
            final var payload = objectMapper.createObjectNode();
            payload.put("type", "context");
            payload.set("terms", objectMapper.valueToTree(terms));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            throw invalid();
        }
    }

    private static String normalizeTerm(final String value) {
        if (value.codePoints().anyMatch(LiveStreamControlFrame::isControlCategory)) {
            throw invalid();
        }
        final String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        final StringBuilder collapsed = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            final int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = collapsed.length() > 0;
                continue;
            }
            if (pendingSpace) {
                collapsed.append(' ');
                pendingSpace = false;
            }
            collapsed.appendCodePoint(codePoint);
        }
        final String term = collapsed.toString();
        final int termChars = term.codePointCount(0, term.length());
        if (termChars == 0 || termChars > MAX_CONTEXT_TERM_CHARS
                || term.codePoints().anyMatch(codePoint ->
                        !isLetterMarkOrNumber(codePoint)
                                && !ALLOWED_PUNCTUATION.contains(codePoint))) {
            throw invalid();
        }
        return term;
    }

    private static boolean isControlCategory(final int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE,
                    Character.SURROGATE, Character.UNASSIGNED -> true;
            default -> false;
        };
    }

    private static boolean isLetterMarkOrNumber(final int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER,
                    Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER, Character.NON_SPACING_MARK,
                    Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
                    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER,
                    Character.OTHER_NUMBER -> true;
            default -> false;
        };
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("live stream control is invalid");
    }
}
