package com.example.user.keycloak;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

class MicrosoftIdentityReadTest {
    static final String SUBJECT = "12345678-1111-2222-3333-123456789012";
    static final String TENANT = "12345678-1111-2222-3333-123456789013";
    static final String OBJECT = "12345678-1111-2222-3333-123456789014";
    static final String ATTRIBUTES = "{\"entra_tid\":[\"" + TENANT + "\"],\"entra_oid\":[\"" + OBJECT + "\"]}";
    static final String USER_PATH = "/admin/realms/platform-test/users/" + SUBJECT;
    WireMockServer server;
    KeycloakAdminClient client;

    @BeforeEach void start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        var props = new KeycloakAdminApiProperties();
        props.setBaseUrl(server.baseUrl());
        props.setClientSecret("synthetic-test-credential");
        client = new KeycloakAdminClient(props, WebClient.builder());
        server.stubFor(post(urlEqualTo("/realms/platform-test/protocol/openid-connect/token"))
                .willReturn(okJson("{\"access_token\":\"synthetic\",\"expires_in\":300}")));
        user(ATTRIBUTES, true, SUBJECT);
        links("[{\"identityProvider\":\"microsoft\",\"userId\":\"opaque-pairwise-broker-sub\"}]");
    }
    @AfterEach void stop() { server.stop(); }
    void user(String attributes, boolean enabled, String id) {
        server.stubFor(get(urlEqualTo(USER_PATH)).willReturn(okJson("{\"id\":\"" + id
                + "\",\"enabled\":" + enabled + ",\"attributes\":" + attributes + "}")));
    }
    void links(String body) {
        server.stubFor(get(urlEqualTo(USER_PATH + "/federated-identity")).willReturn(okJson(body)));
    }

    @Test void readsExactSubjectAndAdminAttributesNotOpaqueBrokerSubject() {
        var result = client.fetchMicrosoftIdentity(SUBJECT, "microsoft").orElseThrow();
        assertThat(result.tenantId()).isEqualTo(UUID.fromString(TENANT));
        assertThat(result.objectId()).isEqualTo(UUID.fromString(OBJECT));
        server.verify(getRequestedFor(urlEqualTo(USER_PATH)).withHeader("Authorization", equalTo("Bearer synthetic")));
        server.verify(getRequestedFor(urlEqualTo(USER_PATH + "/federated-identity")));
        server.verify(0, getRequestedFor(urlPathEqualTo("/admin/realms/platform-test/users")));
        server.verify(0, putRequestedFor(anyUrl()));
    }

    @ParameterizedTest @ValueSource(strings = {
        "{}", "{\"entra_tid\":[\"bad\"],\"entra_oid\":[\"bad\"]}",
        "{\"entra_tid\":[\"12345678-1111-2222-3333-123456789013\"],\"entra_oid\":[]}",
        "{\"entra_tid\":[\"12345678-1111-2222-3333-123456789013\"],\"entra_oid\":[\"12345678-1111-2222-3333-123456789014\",\"12345678-1111-2222-3333-123456789014\"]}",
        "{\"entra_tid\":[\"12345678-1111-2222-3333-123456789013\"],\"entra_oid\":[\"00000000-0000-0000-0000-000000000000\"]}"
    }) void rejectsMissingInvalidOrAmbiguousAttributes(String attributes) {
        user(attributes, true, SUBJECT);
        assertThat(client.fetchMicrosoftIdentity(SUBJECT, "microsoft")).isEmpty();
        server.verify(0, getRequestedFor(urlEqualTo(USER_PATH + "/federated-identity")));
    }

    @ParameterizedTest @ValueSource(strings = {
        "[]", "{}", "[{\"identityProvider\":\"other\",\"userId\":\"opaque\"}]",
        "[{\"identityProvider\":\"microsoft\",\"userId\":\"\"}]",
        "[{\"identityProvider\":\"microsoft\",\"userId\":\"a\"},{\"identityProvider\":\"microsoft\",\"userId\":\"b\"}]"
    }) void rejectsUnlinkedOrAmbiguousIdentity(String body) {
        links(body);
        assertThat(client.fetchMicrosoftIdentity(SUBJECT, "microsoft")).isEmpty();
    }

    @Test void rejectsDisabledOrWrongKeycloakUser() {
        user(ATTRIBUTES, false, SUBJECT);
        assertThat(client.fetchMicrosoftIdentity(SUBJECT, "microsoft")).isEmpty();
        user(ATTRIBUTES, true, OBJECT);
        assertThat(client.fetchMicrosoftIdentity(SUBJECT, "microsoft")).isEmpty();
    }

    @Test void notFoundDoesNotFallBackToEmail() {
        server.stubFor(get(urlEqualTo(USER_PATH)).willReturn(aResponse().withStatus(404)));
        assertThat(client.fetchMicrosoftIdentity(SUBJECT, "microsoft")).isEmpty();
        server.verify(0, getRequestedFor(urlPathEqualTo("/admin/realms/platform-test/users")));
    }

    @Test void upstreamFailureIsNotReportedAsAnIdentity() {
        server.stubFor(get(urlEqualTo(USER_PATH)).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client.fetchMicrosoftIdentity(SUBJECT, "microsoft")).isInstanceOf(RuntimeException.class);
    }

    @Test void rejectsUntrustedPathInputBeforeNetwork() {
        assertThat(client.fetchMicrosoftIdentity("../other", "microsoft")).isEmpty();
        assertThat(client.fetchMicrosoftIdentity(SUBJECT, "../other")).isEmpty();
        server.verify(0, anyRequestedFor(anyUrl()));
    }
}
