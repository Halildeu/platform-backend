package com.example.user.keycloak;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * KC admin REST contract for the MFA panel proxy (gitops#3211): token mint
 * (client credentials, Basic), user resolve by kc_subject with email
 * fallback, OTP credential enumeration/delete, and the GET-then-PUT phone
 * attribute merge (a blind PUT would wipe every other attribute — the PUT
 * body assertions below are the tripwire for that regression).
 */
class KeycloakAdminClientTest {

    private WireMockServer server;
    private KeycloakAdminClient client;

    private static final String TOKEN_PATH = "/realms/platform-test/protocol/openid-connect/token";
    private static final String KC_SUBJECT = "3520324b-aaaa-bbbb-cccc-000000000001";

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();

        KeycloakAdminApiProperties props = new KeycloakAdminApiProperties();
        props.setBaseUrl(server.baseUrl());
        props.setRealm("platform-test");
        props.setClientId("user-mfa-admin");
        props.setClientSecret("test-secret");
        client = new KeycloakAdminClient(props, WebClient.builder());

        server.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"admtok\",\"expires_in\":300}")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void stubUserById(String phoneJson) {
        server.stubFor(get(urlPathEqualTo("/admin/realms/platform-test/users/" + KC_SUBJECT))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + KC_SUBJECT + "\",\"email\":\"u@acik.com\","
                                + "\"attributes\":" + phoneJson + "}")));
    }

    @Test
    void snapshot_readsPhoneTotpAndRole_withBearerFromClientCredentialsMint() {
        stubUserById("{\"phoneNumber\":[\"+905321234567\"],\"dept\":[\"ops\"]}");
        server.stubFor(get(urlPathEqualTo(
                "/admin/realms/platform-test/users/" + KC_SUBJECT + "/credentials"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"cred-1\",\"type\":\"otp\"},"
                                + "{\"id\":\"cred-2\",\"type\":\"password\"}]")));
        server.stubFor(get(urlPathEqualTo(
                "/admin/realms/platform-test/users/" + KC_SUBJECT + "/role-mappings/realm"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"name\":\"requires-mfa\"},{\"name\":\"ADMIN\"}]")));

        Optional<KeycloakAdminClient.MfaSnapshot> snapshot =
                client.fetchMfaSnapshot(KC_SUBJECT, "u@acik.com");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().requiresMfa()).isTrue();
        assertThat(snapshot.get().totpConfigured()).isTrue();
        assertThat(snapshot.get().phoneNumber()).isEqualTo("+905321234567");

        server.verify(exactly(1), postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials(
                        "user-mfa-admin", "test-secret"))
                .withRequestBody(containing("grant_type=client_credentials")));
        server.verify(getRequestedForWithBearer(
                "/admin/realms/platform-test/users/" + KC_SUBJECT));
    }

    private static com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
            getRequestedForWithBearer(String path) {
        return com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer admtok"));
    }

    @Test
    void snapshot_fallsBackToEmailSearch_whenSubjectMissing() {
        server.stubFor(get(urlPathEqualTo("/admin/realms/platform-test/users/" + KC_SUBJECT))
                .willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlPathEqualTo("/admin/realms/platform-test/users"))
                .withQueryParam("email", equalTo("u@acik.com"))
                .withQueryParam("exact", equalTo("true"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"by-email-id\",\"attributes\":{}}]")));
        server.stubFor(get(urlPathEqualTo(
                "/admin/realms/platform-test/users/by-email-id/credentials"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("[]")));
        server.stubFor(get(urlPathEqualTo(
                "/admin/realms/platform-test/users/by-email-id/role-mappings/realm"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("[]")));

        Optional<KeycloakAdminClient.MfaSnapshot> snapshot =
                client.fetchMfaSnapshot(KC_SUBJECT, "u@acik.com");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().kcUserId()).isEqualTo("by-email-id");
        assertThat(snapshot.get().totpConfigured()).isFalse();
        assertThat(snapshot.get().requiresMfa()).isFalse();
        assertThat(snapshot.get().phoneNumber()).isNull();
    }

    @Test
    void deleteOtpCredentials_deletesOnlyOtpType() {
        server.stubFor(get(urlPathEqualTo(
                "/admin/realms/platform-test/users/kc-1/credentials"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"cred-otp\",\"type\":\"otp\"},"
                                + "{\"id\":\"cred-pw\",\"type\":\"password\"}]")));
        server.stubFor(delete(urlPathEqualTo(
                "/admin/realms/platform-test/users/kc-1/credentials/cred-otp"))
                .willReturn(aResponse().withStatus(204)));

        int deleted = client.deleteOtpCredentials("kc-1");

        assertThat(deleted).isEqualTo(1);
        server.verify(exactly(1), deleteRequestedFor(urlPathEqualTo(
                "/admin/realms/platform-test/users/kc-1/credentials/cred-otp")));
        server.verify(exactly(0), deleteRequestedFor(urlPathEqualTo(
                "/admin/realms/platform-test/users/kc-1/credentials/cred-pw")));
    }

    @Test
    void setPhoneAttribute_mergesViaGetThenPut_preservingOtherAttributes() {
        server.stubFor(get(urlPathEqualTo("/admin/realms/platform-test/users/kc-1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"kc-1\",\"email\":\"u@acik.com\","
                                + "\"attributes\":{\"dept\":[\"ops\"]}}")));
        server.stubFor(put(urlPathEqualTo("/admin/realms/platform-test/users/kc-1"))
                .willReturn(aResponse().withStatus(204)));

        client.setPhoneAttribute("kc-1", "+905321234567");

        server.verify(exactly(1), putRequestedFor(urlPathEqualTo(
                "/admin/realms/platform-test/users/kc-1"))
                .withRequestBody(matchingJsonPath("$.attributes.phoneNumber[0]",
                        equalTo("+905321234567")))
                // The merge invariant: the pre-existing attribute must survive.
                .withRequestBody(matchingJsonPath("$.attributes.dept[0]", equalTo("ops"))));
    }

    @Test
    void setPhoneAttribute_null_clearsTheAttribute() {
        server.stubFor(get(urlPathEqualTo("/admin/realms/platform-test/users/kc-1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"kc-1\","
                                + "\"attributes\":{\"phoneNumber\":[\"+905321234567\"],"
                                + "\"dept\":[\"ops\"]}}")));
        server.stubFor(put(urlPathEqualTo("/admin/realms/platform-test/users/kc-1"))
                .willReturn(aResponse().withStatus(204)));

        client.setPhoneAttribute("kc-1", null);

        server.verify(exactly(1), putRequestedFor(urlPathEqualTo(
                "/admin/realms/platform-test/users/kc-1"))
                .withRequestBody(matchingJsonPath("$.attributes.dept[0]", equalTo("ops")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .notMatching(".*phoneNumber.*")));
    }

    @Test
    void disabledWhenSecretBlank() {
        KeycloakAdminApiProperties blank = new KeycloakAdminApiProperties();
        blank.setClientSecret("");
        KeycloakAdminClient disabled = new KeycloakAdminClient(blank, WebClient.builder());
        assertThat(disabled.isEnabled()).isFalse();
    }

    // ── requires-mfa toggle (gitops#3228) ────────────────────────────────

    private static final String ASSIGNED =
            "/admin/realms/platform-test/users/" + KC_SUBJECT + "/role-mappings/realm";
    private static final String AVAILABLE = ASSIGNED + "/available";

    private void stubRoleLists(String assignedJson, String availableJson) {
        server.stubFor(get(urlPathEqualTo(ASSIGNED)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(assignedJson)));
        server.stubFor(get(urlPathEqualTo(AVAILABLE)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(availableJson)));
        server.stubFor(post(urlPathEqualTo(ASSIGNED)).willReturn(aResponse().withStatus(204)));
        server.stubFor(delete(urlPathEqualTo(ASSIGNED)).willReturn(aResponse().withStatus(204)));
    }

    @Test
    void enabling_takesTheRoleFromTheUserScopedAvailableList_notARealmWideRoleRead() {
        // The service account is deliberately not granted view-realm: a live
        // probe returns 403 on /roles/{name} while the user-scoped lists
        // return 200. Reading the representation from `available` is what
        // keeps the grant at view-users + manage-users.
        stubRoleLists("[{\"id\":\"r-admin\",\"name\":\"ADMIN\"}]",
                "[{\"id\":\"r-mfa\",\"name\":\"requires-mfa\"}]");

        assertThat(client.setRequiresMfa(KC_SUBJECT, true)).isTrue();

        server.verify(exactly(1), postRequestedFor(urlPathEqualTo(ASSIGNED))
                .withRequestBody(matchingJsonPath("$[0].id", equalTo("r-mfa")))
                .withRequestBody(matchingJsonPath("$[0].name", equalTo("requires-mfa"))));
        server.verify(exactly(0), com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/admin/realms/platform-test/roles/requires-mfa")));
    }

    @Test
    void disabling_deletesTheAssignedRole() {
        stubRoleLists("[{\"id\":\"r-mfa\",\"name\":\"requires-mfa\"}]", "[]");

        assertThat(client.setRequiresMfa(KC_SUBJECT, false)).isTrue();

        server.verify(exactly(1), deleteRequestedFor(urlPathEqualTo(ASSIGNED))
                .withRequestBody(matchingJsonPath("$[0].name", equalTo("requires-mfa"))));
    }

    @Test
    void askingForTheStateAlreadyHeld_writesNothing() {
        // A double click must agree with reality rather than error.
        stubRoleLists("[{\"id\":\"r-mfa\",\"name\":\"requires-mfa\"}]", "[]");
        assertThat(client.setRequiresMfa(KC_SUBJECT, true)).isFalse();

        stubRoleLists("[]", "[{\"id\":\"r-mfa\",\"name\":\"requires-mfa\"}]");
        assertThat(client.setRequiresMfa(KC_SUBJECT, false)).isFalse();

        server.verify(exactly(0), postRequestedFor(urlPathEqualTo(ASSIGNED)));
        server.verify(exactly(0), deleteRequestedFor(urlPathEqualTo(ASSIGNED)));
    }

    @Test
    void realmWithoutTheRole_failsLoudlyInsteadOfReportingSuccess() {
        // Silently succeeding would leave an operator believing the second
        // factor is enforced when the flow condition can never match.
        stubRoleLists("[]", "[{\"id\":\"r-other\",\"name\":\"SOMETHING_ELSE\"}]");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> client.setRequiresMfa(KC_SUBJECT, true))
                .isInstanceOf(KeycloakAdminClient.RequiresMfaRoleMissingException.class)
                .hasMessageContaining("requires-mfa");

        server.verify(exactly(0), postRequestedFor(urlPathEqualTo(ASSIGNED)));
    }
}
