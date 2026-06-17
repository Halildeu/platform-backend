package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.PtyArgumentPolicy.CommandSpec;
import com.example.endpointadmin.remoteaccess.PtyArgumentPolicy.ValueSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Broker-side half of the cross-repo PtyArgumentPolicy drift guard. The fixture is the committed vector the
 * agent consumes; this test proves it still matches the broker's authoritative constants.
 */
class PtyArgumentPolicyGoldenVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void committedGoldenVectorMatchesBrokerPilotPolicy() throws Exception {
        JsonNode vector;
        try (InputStream in = getClass().getResourceAsStream("/remote-bridge/pty-arg-policy-vector.json")) {
            assertNotNull(in, "committed broker argument-policy vector must be on the test classpath");
            vector = MAPPER.readTree(in);
        }

        assertEquals(1, vector.get("schemaVersion").asInt());
        assertEquals("pilot-default-v1", vector.get("policyVersion").asText());
        assertEquals(PtyArgumentPolicy.MAX_LINE, vector.get("maxLine").asInt());
        assertEquals(PtyArgumentPolicy.MAX_VALUE_LEN, vector.get("maxValueLen").asInt());

        Map<String, CommandSpec> specs = PtyArgumentPolicy.PILOT_DEFAULT_POLICY.commandSpecs();
        assertEquals(PtyCommandGuard.PILOT_DEFAULT_ALLOWLIST, specs.keySet());
        JsonNode commands = vector.get("commands");
        assertNotNull(commands, "commands object is required");
        assertEquals(new TreeSet<>(specs.keySet()), new TreeSet<>(fieldNames(commands)));

        for (Map.Entry<String, CommandSpec> entry : specs.entrySet()) {
            String command = entry.getKey();
            CommandSpec spec = entry.getValue();
            JsonNode node = commands.get(command);
            assertNotNull(node, "missing command in vector: " + command);

            assertEquals(sorted(spec.valuelessFlags()), sorted(textArray(node.get("valuelessFlags"))),
                    command + " valuelessFlags drifted");
            assertEquals(sorted(spec.forbiddenFlags()), sorted(textArray(node.get("forbiddenFlags"))),
                    command + " forbiddenFlags drifted");
            assertEquals(spec.operandRule().name(), node.get("operandRule").asText(),
                    command + " operandRule drifted");
            assertEquals(spec.maxOperands(), node.get("maxOperands").asInt(),
                    command + " maxOperands drifted");

            JsonNode valueFlags = node.get("valueFlags");
            assertNotNull(valueFlags, command + " valueFlags object is required");
            assertEquals(new TreeSet<>(spec.valueFlags().keySet()), new TreeSet<>(fieldNames(valueFlags)),
                    command + " value-flag keys drifted");
            for (Map.Entry<String, ValueSpec> vf : spec.valueFlags().entrySet()) {
                assertValueSpec(command, vf.getKey(), vf.getValue(), valueFlags.get(vf.getKey()));
            }
        }
    }

    private static void assertValueSpec(String command, String flag, ValueSpec spec, JsonNode node) {
        assertNotNull(node, command + " missing value flag " + flag);
        assertEquals(spec.sensitive(), node.get("sensitive").asBoolean(), command + " " + flag + " sensitivity drifted");
        if (spec.integer()) {
            assertEquals("integer", node.get("type").asText(), command + " " + flag + " type drifted");
            assertEquals(spec.min(), node.get("min").asLong(), command + " " + flag + " min drifted");
            assertEquals(spec.max(), node.get("max").asLong(), command + " " + flag + " max drifted");
            assertFalse(node.has("values"), command + " " + flag + " integer spec must not carry enum values");
        } else {
            assertEquals("enum", node.get("type").asText(), command + " " + flag + " type drifted");
            assertEquals(sorted(spec.enumValues()), sorted(textArray(node.get("values"))),
                    command + " " + flag + " enum values drifted");
            assertFalse(node.has("min"), command + " " + flag + " enum spec must not carry min");
            assertFalse(node.has("max"), command + " " + flag + " enum spec must not carry max");
        }
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> out = new ArrayList<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static Set<String> textArray(JsonNode node) {
        assertNotNull(node, "array node is required");
        assertTrue(node.isArray(), "expected array, got " + node);
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText().toLowerCase(Locale.ROOT)));
        return Set.copyOf(out);
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().collect(Collectors.toList());
    }
}
