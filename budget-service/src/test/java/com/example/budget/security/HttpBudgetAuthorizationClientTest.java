package com.example.budget.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class HttpBudgetAuthorizationClientTest {
    private MockRestServiceServer server;
    private HttpBudgetAuthorizationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpBudgetAuthorizationClient(builder, "http://permission-service");
    }

    @Test
    void parsesAuthoritativeCompanyAndProjectScopes() {
        server.expect(requestTo("http://permission-service/api/v1/authz/me"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer browser-token"))
                .andRespond(withSuccess("""
                        {
                          "userId":"1204",
                          "superAdmin":false,
                          "allowedScopes":[
                            {"scopeType":"COMPANY","scopeRefId":35},
                            {"scopeType":"PROJECT","scopeRefId":44200},
                            {"scopeType":"WAREHOUSE","scopeRefId":8}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var snapshot = client.fetch("browser-token");

        assertThat(snapshot.userId()).isEqualTo("1204");
        assertThat(snapshot.allowedCompanyIds()).containsExactly(35L);
        assertThat(snapshot.allowedProjectIds()).containsExactly(44200L);
        assertThat(snapshot.superAdmin()).isFalse();
        server.verify();
    }

    @Test
    void locallyAcceptedTokenRejectedUpstreamIsContractFailure() {
        server.expect(requestTo("http://permission-service/api/v1/authz/me"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.fetch("browser-token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void upstreamOutageIsServiceUnavailableNotEmptyAuthorization() {
        server.expect(requestTo("http://permission-service/api/v1/authz/me"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.fetch("browser-token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
