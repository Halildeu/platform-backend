package com.example.auth.controller;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.auth.serviceauth.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;

class SpeakerLabelMintConfigurationTest {
    @ParameterizedTest @ValueSource(strings = {"application.properties", "application-k8s.yml"})
    void shippedConfigurationRequiresExplicitGrantAndDoesNotGrantAnalysisWorkerNames(String resource) throws Exception {
        var environment = new StandardEnvironment();
        var shipped = new FileSystemResource("src/main/resources/" + resource);
        var source = resource.endsWith("yml") ? new YamlPropertySourceLoader().load("shipped", shipped)
                : new PropertiesPropertySourceLoader().load("shipped", shipped);
        source.forEach(s -> environment.getPropertySources().addLast(s));
        var clients = Binder.get(environment).bind("security.service-clients", ServiceClientsProperties.class).orElseThrow(IllegalStateException::new);
        var policy = Binder.get(environment).bind("security.service-mint", ServiceMintPolicyProperties.class).orElseThrow(IllegalStateException::new);
        clients.getClients().get("meeting-service").setSecret("test-only");
        clients.getClients().get("meeting-ai").setSecret("test-only");
        var provider = mock(ServiceTokenProvider.class);
        var controller = new ServiceTokenController(provider, clients, policy);
        for (String permission : List.of("transcript:speaker-label:read", "transcript:speaker-label:write")) {
            assertDenied(controller, "meeting-service", "transcript-service", permission);
            assertDenied(controller, "meeting-ai", "transcript-service", permission);
        }
        verifyNoInteractions(provider);
        // Explicit enablement needs all three ceilings. Keep every unrelated client unchanged.
        var meeting = clients.getClients().get("meeting-service");
        for (String permission : List.of("transcript:speaker-label:read", "transcript:speaker-label:write")) {
            policy.getAllowedPermissions().add(permission);
            meeting.getAllowedPermissions().add(permission);
            meeting.getAllowedPermissionsByAudience().get("transcript-service").add(permission);
            when(provider.getTokenForClient("meeting-service", "transcript-service", List.of(permission))).thenReturn("test-token");
            assertThat(controller.token(Map.of(), form("meeting-service", "transcript-service", permission)).getStatusCode().value()).isEqualTo(200);
            assertDenied(controller, "meeting-service", "user-service", permission);
            assertDenied(controller, "meeting-ai", "transcript-service", permission);
        }
    }
    private static LinkedMultiValueMap<String,String> form(String client, String audience, String permission) {
        var form = new LinkedMultiValueMap<String,String>();
        form.add("grant_type", "client_credentials"); form.add("client_id", client); form.add("client_secret", "test-only");
        form.add("audience", audience); form.add("permissions", permission); return form;
    }
    private static void assertDenied(ServiceTokenController controller, String client, String audience, String permission) {
        assertThatThrownBy(() -> controller.token(Map.of(), form(client, audience, permission)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
    }
}
