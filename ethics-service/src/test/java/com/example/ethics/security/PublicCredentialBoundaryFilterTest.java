package com.example.ethics.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ethics.config.EthicsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicCredentialBoundaryFilterTest {
    private final PublicCredentialBoundaryFilter filter = new PublicCredentialBoundaryFilter(
            new ObjectMapper(),
            new EthicsProperties(
                    UUID.fromString("00000000-0000-0000-0000-000000000035"),
                    Duration.ofMinutes(15),
                    210_000,
                    "ethics-manager",
                    true));

    @Test
    void publicApiRejectsRequestsWithoutIngressTransportProof() throws Exception {
        var request = publicRequest();
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("SECURE_TRANSPORT_REQUIRED");
    }

    @Test
    void publicApiAcceptsExactIngressTransportProof() throws Exception {
        var request = publicRequest();
        request.addHeader(PublicCredentialBoundaryFilter.TRANSPORT_HEADER, "https");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void transportProofDoesNotBypassCredentialConfusionBoundary() throws Exception {
        var request = publicRequest();
        request.addHeader(PublicCredentialBoundaryFilter.TRANSPORT_HEADER, "https");
        request.addHeader("Authorization", "Basic edge-credential-must-be-stripped");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("CREDENTIAL_CONFUSION");
    }

    private static MockHttpServletRequest publicRequest() {
        var request = new MockHttpServletRequest("POST", "/api/v1/public/ethics/reports");
        request.setRequestURI("/api/v1/public/ethics/reports");
        return request;
    }
}
