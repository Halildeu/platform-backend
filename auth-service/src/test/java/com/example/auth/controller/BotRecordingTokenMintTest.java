package com.example.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.example.auth.serviceauth.ServiceClientsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises OAuth minting using the REAL per-client ceilings from both delivery configurations. */
@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-mint.allowed-audiences=meeting-service,audit-event-consumer-service,user-service,transcript-service,notification-orchestrator",
        "security.service-mint.allowed-permissions=meeting:bot-recording:admit,audit:bot-recording:manage,users:internal,transcript:canonical:read,notify:intents:system",
        "security.service-mint.rate-limit-per-minute=1000", "security.service-mint.failed-auth-rate-limit-per-minute=1000",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token", "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:botmint;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.vault.enabled=false", "management.health.vault.enabled=false", "spring.main.allow-bean-definition-overriding=true"
})
class BotRecordingTokenMintTest {
    @Autowired MockMvc mvc;
    @Autowired ServiceClientsProperties clients;
    @ParameterizedTest @ValueSource(strings = {"application.properties", "application-k8s.yml"})
    void workerOnlyAdmitsAndMeetingOnlyManagesAtExactAudience(String config) throws Exception {
        var file = new FileSystemResource(Path.of("src/main/resources", config));
        var sources = config.endsWith(".yml") ? new YamlPropertySourceLoader().load("real", file)
                : new PropertiesPropertySourceLoader().load("real", file);
        var environment = new StandardEnvironment(); sources.forEach(source -> environment.getPropertySources().addFirst(source));
        var actual = Binder.get(environment).bind("security.service-clients", ServiceClientsProperties.class).get();
        var previous = clients.getClients(); clients.setClients(actual.getClients());
        clients.getClients().get("meeting-service").setSecret("synthetic-bot");
        clients.getClients().get("teams-capture-worker").setSecret("synthetic-bot");
        try {
            successful("meeting-service", "audit-event-consumer-service", "audit:bot-recording:manage");
            successful("teams-capture-worker", "meeting-service", "meeting:bot-recording:admit");
            for (String audience : new String[] {"user-service", "transcript-service", "notification-orchestrator", "meeting-service"})
                rejected("meeting-service", audience, "audit:bot-recording:manage");
            rejected("meeting-service", "audit-event-consumer-service", "users:internal");
            rejected("teams-capture-worker", "audit-event-consumer-service", "audit:bot-recording:manage");
            rejected("teams-capture-worker", "meeting-service", "audit:bot-recording:manage");
            rejected("meeting-service", "audit-event-consumer-service", null);
            rejected("teams-capture-worker", "meeting-service", null);
            clients.getClients().get("meeting-service").setSecret("");
            mvc.perform(request("meeting-service", "audit-event-consumer-service", "audit:bot-recording:manage")).andExpect(status().isUnauthorized());
        } finally { clients.setClients(previous); }
    }
    private void successful(String client, String audience, String permission) throws Exception {
        var response = mvc.perform(request(client, audience, permission)).andExpect(status().isOk()).andReturn();
        var json = new ObjectMapper().readTree(response.getResponse().getContentAsString());
        var claims = com.nimbusds.jwt.SignedJWT.parse(json.get("access_token").asText()).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly(audience); assertThat(claims.getSubject()).isEqualTo(client);
        assertThat(claims.getStringClaim("client_id")).isEqualTo(client); assertThat(claims.getStringListClaim("perm")).containsExactly(permission);
    }
    private void rejected(String client, String audience, String permission) throws Exception {
        mvc.perform(request(client, audience, permission)).andExpect(status().is4xxClientError());
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String client, String audience, String permission) {
        String basic = Base64.getEncoder().encodeToString((client + ":synthetic-bot").getBytes(StandardCharsets.UTF_8));
        return post("/oauth2/token").header("Authorization", "Basic " + basic).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("audience", audience).param("permissions", permission == null ? "" : permission);
    }
}
