package com.example.endpointadmin.remoteaccess.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Immutable GitOps-mounted schemas and platform safety baseline. */
public record RemoteViewPolicyArtifacts(
        JsonSchema tenantPolicySchema,
        JsonSchema baselineSchema,
        JsonSchema envelopeSchema,
        JsonNode baseline) {

    public static RemoteViewPolicyArtifacts load(RemoteViewPolicyProperties properties,
                                                 RemoteViewJsonCanonicalizer canonicalizer) {
        requirePath(properties.tenantPolicySchemaPath(), "tenant-policy-schema-path");
        requirePath(properties.baselineSchemaPath(), "baseline-schema-path");
        requirePath(properties.envelopeSchemaPath(), "envelope-schema-path");
        requirePath(properties.baselinePath(), "baseline-path");
        try {
            SchemaValidatorsConfig config = new SchemaValidatorsConfig();
            config.setFormatAssertionsEnabled(true);
            config.setTypeLoose(false);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            JsonNode tenantSchemaNode = canonicalizer.strictParse(
                    Files.readString(Path.of(properties.tenantPolicySchemaPath())));
            JsonNode baselineSchemaNode = canonicalizer.strictParse(
                    Files.readString(Path.of(properties.baselineSchemaPath())));
            JsonNode envelopeSchemaNode = canonicalizer.strictParse(
                    Files.readString(Path.of(properties.envelopeSchemaPath())));
            JsonNode baseline = canonicalizer.strictParse(Files.readString(Path.of(properties.baselinePath())));
            JsonSchema tenantSchema = factory.getSchema(tenantSchemaNode, config);
            JsonSchema baselineSchema = factory.getSchema(baselineSchemaNode, config);
            JsonSchema envelopeSchema = factory.getSchema(envelopeSchemaNode, config);
            if (!baselineSchema.validate(baseline).isEmpty()) {
                throw new IllegalStateException("remote-view platform baseline does not validate against its schema");
            }
            return new RemoteViewPolicyArtifacts(tenantSchema, baselineSchema, envelopeSchema, baseline.deepCopy());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("remote-view-policy authority artifacts are invalid; refusing startup", e);
        }
    }

    private static void requirePath(String value, String name) {
        if (value == null || value.isBlank() || !Files.isRegularFile(Path.of(value))) {
            throw new IllegalStateException("remote-view-policy." + name + " must reference a readable file");
        }
    }
}
