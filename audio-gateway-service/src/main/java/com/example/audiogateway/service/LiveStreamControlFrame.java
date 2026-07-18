package com.example.audiogateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;

/** Strict, bounded client-to-gateway terminal control frame. */
public record LiveStreamControlFrame(Type type) {

    public static final String CANONICAL_EOF = "{\"type\":\"eof\"}";

    public enum Type {
        EOF
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
        if (root == null
                || !root.isObject()
                || root.size() != 1
                || !root.path("type").isTextual()
                || !"eof".equals(root.path("type").textValue())) {
            throw invalid();
        }
        return new LiveStreamControlFrame(Type.EOF);
    }

    public String upstreamPayload() {
        return CANONICAL_EOF;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("live stream terminal control is invalid");
    }
}
