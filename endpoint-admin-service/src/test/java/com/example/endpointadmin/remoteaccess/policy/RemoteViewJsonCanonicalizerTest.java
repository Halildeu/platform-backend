package com.example.endpointadmin.remoteaccess.policy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteViewJsonCanonicalizerTest {
    private final RemoteViewJsonCanonicalizer canonicalizer = new RemoteViewJsonCanonicalizer();

    @Test
    void matchesGitOpsGoldenDigestsForBaselineAndTurkishPolicy() throws Exception {
        assertThat(canonicalizer.digest(canonicalizer.strictParse(
                Files.readString(RemoteViewPolicyTestSupport.fixture("baseline.json")))))
                .isEqualTo("sha256:54132a1b4d035db7011f1ce200433234aea7fe0d04420f0928dcf26a06386337");
        assertThat(canonicalizer.digest(canonicalizer.strictParse(
                Files.readString(RemoteViewPolicyTestSupport.fixture("example-policy.json")))))
                .isEqualTo("sha256:932397b4844474922392324a00c84457db026a5d00de837f1fd2daf8985c86d4");
    }

    @Test
    void canonicalizesSemanticObjectIndependentOfMemberOrderAndWhitespace() {
        assertThat(canonicalizer.canonicalString(canonicalizer.strictParse("{\"z\":2, \"a\":\"Türkçe\"}")))
                .isEqualTo("{\"a\":\"Türkçe\",\"z\":2}");
    }

    @Test
    void rejectsDuplicateKeysFloatsExponentsUnsafeIntegersAndTrailingJson() {
        for (String invalid : new String[]{
                "{\"a\":1,\"a\":2}", "{\"a\":1.0}", "{\"a\":1e0}",
                "{\"a\":9007199254740992}", "{\"a\":1}{\"b\":2}", "{\"a\":\"\\uD800\"}"}) {
            assertThatThrownBy(() -> canonicalizer.strictParse(invalid))
                    .isInstanceOf(RemoteViewPolicyException.class)
                    .extracting(e -> ((RemoteViewPolicyException) e).reason())
                    .isEqualTo(RemoteViewPolicyReason.POLICY_INVALID);
        }
    }
}
