package com.example.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.auth.serviceauth.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;

/** Exercises the actual source configuration and controller, not a copied test policy. */
class TranscriptNotificationPolicyTest {
    record Fixture(ServiceTokenController controller, ServiceTokenProvider provider, ServiceClientsProperties clients) {}
    Fixture fixture(String profile) throws Exception {
        var env = new MockEnvironment();
        if (profile.equals("k8s")) {
            new YamlPropertySourceLoader().load("production", new FileSystemResource("src/main/resources/application-k8s.yml"))
                .forEach(source -> env.getPropertySources().addLast(source));
        } else {
            var props = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8)) { props.load(reader); }
            env.getPropertySources().addLast(new PropertiesPropertySource("production", props));
        }
        var clients = Binder.get(env).bind("security.service-clients", ServiceClientsProperties.class).get();
        var policy = Binder.get(env).bind("security.service-mint", ServiceMintPolicyProperties.class).get();
        clients.getClients().get("transcript-service").setSecret("test-only");
        var provider = mock(ServiceTokenProvider.class);
        when(provider.getTokenForClient(anyString(), anyString(), anyList())).thenReturn("synthetic-token");
        return new Fixture(new ServiceTokenController(provider, clients, policy), provider, clients);
    }
    void request(Fixture f, String client, String audience, String permission) {
        var form = new LinkedMultiValueMap<String,String>();
        form.add("grant_type", "client_credentials"); form.add("client_id", client); form.add("client_secret", "test-only");
        form.add("audience", audience);
        if (permission != null) form.add("permissions", permission);
        f.controller().token(Map.of(), form);
    }
    @ParameterizedTest @ValueSource(strings={"default","k8s"})
    void approvedPermissionsArePinnedToTheirAudiences(String profile) throws Exception {
        var f=fixture(profile);
        request(f,"transcript-service","meeting-service","meeting:notification:read");
        request(f,"transcript-service","meeting-service","meeting:session:resolve");
        request(f,"transcript-service","notification-orchestrator","notify:intents:system");
        verify(f.provider()).getTokenForClient("transcript-service","meeting-service",List.of("meeting:notification:read"));
        verify(f.provider()).getTokenForClient("transcript-service","meeting-service",List.of("meeting:session:resolve"));
        verify(f.provider()).getTokenForClient("transcript-service","notification-orchestrator",List.of("notify:intents:system"));
    }
    @ParameterizedTest @ValueSource(strings={"default","k8s"})
    void wrongAudienceAndUnrelatedPermissionsCannotMint(String profile) throws Exception {
        var f=fixture(profile);
        assertThatThrownBy(() -> request(f,"transcript-service","meeting-service","notify:intents:system")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> request(f,"transcript-service","notification-orchestrator","meeting:notification:read")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> request(f,"transcript-service","meeting-service","meeting:analysis-result:write")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> request(f,"transcript-service","user-service","users:internal")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> request(f,"transcript-service","meeting-service",null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(f.provider());
    }
    @ParameterizedTest @ValueSource(strings={"default","k8s"})
    void globalAllowlistDoesNotGrantOtherClientsTheNewPermission(String profile) throws Exception {
        var f=fixture(profile); f.clients().getClients().get("meeting-ai").setSecret("test-only");
        assertThatThrownBy(() -> request(f,"meeting-ai","meeting-service","meeting:notification:read")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(f.provider());
    }
    @ParameterizedTest @ValueSource(strings={"default","k8s"})
    void unprovisionedClientRemainsDisabled(String profile) throws Exception {
        var f=fixture(profile); f.clients().getClients().get("transcript-service").setSecret("");
        assertThatThrownBy(() -> request(f,"transcript-service","meeting-service","meeting:notification:read"))
            .isInstanceOf(ResponseStatusException.class).satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(f.provider());
    }
}
