package com.example.endpointadmin.remoteaccess.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteViewPolicyFixtureProvenanceTest {
    private static final String AUTHORITY_COMMIT = "72c95be75f95946990c7a49fc71bb17057992413";

    @Test
    void fixturesRemainPinnedToTheReviewedGitOpsContractCommit() throws Exception {
        Path directory = RemoteViewPolicyTestSupport.fixture("fixture-provenance.json").getParent();
        JsonNode manifest = new ObjectMapper().readTree(Files.readString(
                directory.resolve("fixture-provenance.json")));

        assertThat(manifest.path("authorityRepository").asText()).isEqualTo("Halildeu/platform-k8s-gitops");
        assertThat(manifest.path("authorityCommit").asText()).isEqualTo(AUTHORITY_COMMIT);
        assertThat(manifest.path("files")).hasSize(5);
        for (JsonNode entry : manifest.path("files")) {
            Path fixture = directory.resolve(entry.path("fixture").asText());
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture)));
            assertThat(actual)
                    .as("%s from %s", entry.path("fixture").asText(), entry.path("authorityPath").asText())
                    .isEqualTo(entry.path("sha256").asText());
        }
    }
}
