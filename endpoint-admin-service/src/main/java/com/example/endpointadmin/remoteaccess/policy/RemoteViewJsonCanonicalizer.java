package com.example.endpointadmin.remoteaccess.policy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Strict JSON value parsing plus RFC 8785 canonical bytes. */
public final class RemoteViewJsonCanonicalizer {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final ObjectMapper mapper;

    public RemoteViewJsonCanonicalizer() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        mapper = new ObjectMapper(factory)
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public JsonNode strictParse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("JSON body is required", null);
        }
        try (JsonParser parser = mapper.createParser(raw)) {
            JsonNode value = mapper.readTree(parser);
            rejectUnsupportedValues(value, "$");
            return value;
        } catch (IOException | RuntimeException e) {
            if (e instanceof RemoteViewPolicyException policyException) {
                throw policyException;
            }
            throw invalid("JSON is not a strict policy value", e);
        }
    }

    public JsonNode parseCanonical(String canonical) {
        return strictParse(canonical);
    }

    public byte[] canonicalBytes(JsonNode value) {
        rejectUnsupportedValues(value, "$");
        try {
            return new JsonCanonicalizer(mapper.writeValueAsBytes(value)).getEncodedUTF8();
        } catch (IOException e) {
            throw invalid("Policy cannot be canonicalized", e);
        }
    }

    public String canonicalString(JsonNode value) {
        return new String(canonicalBytes(value), StandardCharsets.UTF_8);
    }

    public String digest(JsonNode value) {
        return digest(canonicalBytes(value));
    }

    public String digest(byte[] canonicalBytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    private static void rejectUnsupportedValues(JsonNode node, String path) {
        if (node == null) {
            throw invalid("Null JSON tree at " + path, null);
        }
        if (node.isFloatingPointNumber()) {
            throw invalid("Floating point JSON numbers are forbidden at " + path, null);
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong() || node.longValue() < -MAX_SAFE_INTEGER
                    || node.longValue() > MAX_SAFE_INTEGER) {
                throw invalid("JSON integer is outside the interoperable JCS range at " + path, null);
            }
        } else if (node.isTextual()) {
            rejectUnpairedSurrogates(node.textValue(), path);
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                rejectUnsupportedValues(node.get(i), path + "[" + i + "]");
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                rejectUnpairedSurrogates(entry.getKey(), path);
                rejectUnsupportedValues(entry.getValue(), path + "." + entry.getKey());
            });
        }
    }

    private static void rejectUnpairedSurrogates(String text, String path) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++i))) {
                    throw invalid("Unpaired Unicode surrogate at " + path, null);
                }
            } else if (Character.isLowSurrogate(current)) {
                throw invalid("Unpaired Unicode surrogate at " + path, null);
            }
        }
    }

    private static RemoteViewPolicyException invalid(String message, Throwable cause) {
        return new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_INVALID, message, cause);
    }
}
