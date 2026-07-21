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

class PublicRateLimitFilterTest {
    private static final int LIMIT = 3;

    private PublicRateLimitFilter newFilter() {
        return new PublicRateLimitFilter(new EthicsProperties(
                UUID.fromString("00000000-0000-0000-0000-000000000035"),
                Duration.ofMinutes(15),
                210_000,
                "ethics-manager",
                "ethics-manager",
                true,
                LIMIT), new ObjectMapper());
    }

    @Test
    void nonPublicEndpointsAreNotThrottled() throws Exception {
        PublicRateLimitFilter filter = newFilter();
        for (int i = 0; i < LIMIT * 4; i++) {
            var request = new MockHttpServletRequest("GET", "/actuator/health");
            request.setRequestURI("/actuator/health");
            request.setRemoteAddr("10.0.0.1");
            request.setServerName("etik.acik.com");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void publicApiIsThrottledOnceThresholdExceeded() throws Exception {
        PublicRateLimitFilter filter = newFilter();
        for (int i = 0; i < LIMIT; i++) {
            var request = publicRequest("10.0.0.1");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var overRequest = publicRequest("10.0.0.1");
        var overResponse = new MockHttpServletResponse();
        filter.doFilter(overRequest, overResponse, new MockFilterChain());
        assertThat(overResponse.getStatus()).isEqualTo(429);
        assertThat(overResponse.getContentAsString()).contains("RATE_LIMITED");
        assertThat(overResponse.getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void bucketsAreIsolatedPerRemoteAddress() throws Exception {
        PublicRateLimitFilter filter = newFilter();
        for (int i = 0; i < LIMIT; i++) {
            var request = publicRequest("10.0.0.1");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var otherRequest = publicRequest("10.0.0.2");
        var otherResponse = new MockHttpServletResponse();
        filter.doFilter(otherRequest, otherResponse, new MockFilterChain());
        assertThat(otherResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void xForwardedForIsRespectedForBucketing() throws Exception {
        PublicRateLimitFilter filter = newFilter();
        for (int i = 0; i < LIMIT; i++) {
            var request = publicRequest("10.0.0.99");
            request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.99");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var overRequest = publicRequest("10.0.0.99");
        overRequest.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.99");
        var overResponse = new MockHttpServletResponse();
        filter.doFilter(overRequest, overResponse, new MockFilterChain());
        assertThat(overResponse.getStatus()).isEqualTo(429);
    }

    private static MockHttpServletRequest publicRequest(String remoteAddr) {
        var request = new MockHttpServletRequest("POST", "/api/v1/public/ethics/reports");
        request.setRequestURI("/api/v1/public/ethics/reports");
        request.setRemoteAddr(remoteAddr);
        request.setServerName("etik.acik.com");
        return request;
    }
}
