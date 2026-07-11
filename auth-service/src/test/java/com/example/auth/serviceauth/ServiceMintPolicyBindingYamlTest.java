package com.example.auth.serviceauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;

/**
 * Proves the production {@code permission-client-bindings} YAML shape used in
 * application-k8s.yml actually binds to {@code Map<String, Set<String>>}.
 *
 * <p>MeetingAiServiceTokenMintTest injects the binding via {@code @BeforeEach} because
 * Spring's relaxed binding cannot parse this map from a flat inline property string (the
 * permission key contains ':'). That is a test-harness limitation, not a runtime one: real
 * config is loaded from YAML, where an explicit nested map+list binds correctly. This test
 * pins that so the write-scope binding can't silently no-op in production and quietly
 * re-open the cross-client escalation.
 */
class ServiceMintPolicyBindingYamlTest {

    private ServiceMintPolicyProperties bindFromYaml(String yaml) {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        StandardEnvironment env = new StandardEnvironment();
        try {
            loader.load(
                            "binding-test",
                            new EncodedResource(new ByteArrayResource(yaml.getBytes())).getResource())
                    .forEach(ps -> env.getPropertySources().addFirst(ps));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load test yaml", e);
        }
        return Binder.get(env)
                .bind("security.service-mint", ServiceMintPolicyProperties.class)
                .get();
    }

    @Test
    void productionYamlShape_bindsPermissionToBoundClients() {
        // Mirrors application-k8s.yml. The "[...]" key quoting is what lets the ':'-bearing
        // permission survive as a single map key.
        String yaml =
                "security:\n"
                        + "  service-mint:\n"
                        + "    allowed-audiences: meeting-service\n"
                        + "    allowed-permissions: meeting:analysis-result:write\n"
                        + "    permission-client-bindings:\n"
                        + "      \"[meeting:analysis-result:write]\":\n"
                        + "        - meeting-ai\n";

        ServiceMintPolicyProperties props = bindFromYaml(yaml);

        assertThat(props.getPermissionClientBindings())
                .containsEntry("meeting:analysis-result:write", Set.of("meeting-ai"));
        assertThat(props.getAllowedAudiences()).containsExactly("meeting-service");
        assertThat(props.getAllowedPermissions()).containsExactly("meeting:analysis-result:write");
    }

    @Test
    void multipleBoundClients_bindAsASet() {
        String yaml =
                "security:\n"
                        + "  service-mint:\n"
                        + "    permission-client-bindings:\n"
                        + "      \"[meeting:analysis-result:write]\":\n"
                        + "        - meeting-ai\n"
                        + "        - meeting-ai-canary\n";

        ServiceMintPolicyProperties props = bindFromYaml(yaml);

        assertThat(props.getPermissionClientBindings())
                .containsEntry(
                        "meeting:analysis-result:write", Set.of("meeting-ai", "meeting-ai-canary"));
    }

    @Test
    void absentBinding_leavesMapEmpty_soLegacyPermissionsStayGlobal() {
        String yaml =
                "security:\n"
                        + "  service-mint:\n"
                        + "    allowed-permissions: permissions:read,permissions:write\n";

        ServiceMintPolicyProperties props = bindFromYaml(yaml);

        assertThat(props.getPermissionClientBindings()).isEmpty();
        assertThat(props.getAllowedPermissions())
                .containsExactlyInAnyOrderElementsOf(List.of("permissions:read", "permissions:write"));
    }
}
