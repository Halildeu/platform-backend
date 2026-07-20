package com.example.ethics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class EthicsEntitlementVerifierTest {
    @Test
    void currentExactPermissionProjectionAllows() {
        assertThat(EthicsEntitlementVerifier.isExactEthicManage(response(
                "41", 41L, false,
                List.of("ETIK_SPEAK_MANAGER"), Map.of("ETHIC", "MANAGE"),
                List.of("ETHIC"), List.of("ETHIC")))).isTrue();
    }

    @Test
    void missingRevokedBroaderOrMismatchedProjectionDenies() {
        assertThat(EthicsEntitlementVerifier.isExactEthicManage(null)).isFalse();
        assertThat(EthicsEntitlementVerifier.isExactEthicManage(response(
                "41", 42L, false,
                List.of("ETIK_SPEAK_MANAGER"), Map.of("ETHIC", "MANAGE"),
                List.of("ETHIC"), List.of("ETHIC")))).isFalse();
        assertThat(EthicsEntitlementVerifier.isExactEthicManage(response(
                "41", 41L, false,
                List.of("ETIK_SPEAK_MANAGER"), Map.of(), List.of(), List.of()))).isFalse();
        assertThat(EthicsEntitlementVerifier.isExactEthicManage(response(
                "41", 41L, true,
                List.of("ETIK_SPEAK_MANAGER"), Map.of("ETHIC", "MANAGE"),
                List.of("ETHIC"), List.of("ETHIC")))).isFalse();
    }

    @Test
    void forwardsBearerAndTreatsDependencyFailureAsDeny() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://permission.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EthicsEntitlementVerifier verifier = new EthicsEntitlementVerifier(builder.build());

        server.expect(once(), requestTo("http://permission.test/api/v1/authz/me"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer current-token"))
                .andRespond(withSuccess("""
                        {"userId":"41","subscriberId":41,"superAdmin":false,
                         "roles":["ETIK_SPEAK_MANAGER"],"modules":{"ETHIC":"MANAGE"},
                         "allowedModules":["ETHIC"],"permissions":["ETHIC"]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(verifier.hasManageEntitlement("current-token")).isTrue();
        server.verify();

        server.reset();
        server.expect(once(), requestTo("http://permission.test/api/v1/authz/me"))
                .andRespond(withServerError());
        assertThat(verifier.hasManageEntitlement("revoked-or-unavailable-token")).isFalse();
        server.verify();
    }

    private EthicsEntitlementVerifier.AuthzMeResponse response(
            String userId,
            Long subscriberId,
            boolean superAdmin,
            List<String> roles,
            Map<String, String> modules,
            List<String> allowedModules,
            List<String> permissions) {
        return new EthicsEntitlementVerifier.AuthzMeResponse(
                userId, subscriberId, superAdmin, roles, modules, allowedModules, permissions);
    }
}
