package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 22.5 Step-2 — bidirectional connector guard (Codex {@code 019ec0f9}
 * Slice 1b). endpoint-agent traffic only on the mTLS port; mTLS port only
 * serves endpoint-agent.
 */
class MtlsConnectorGuardFilterTest {

    private static final int MTLS_PORT = 8443;
    private static final int PLAIN_PORT = 8096;

    private final MtlsConnectorGuardFilter filter = new MtlsConnectorGuardFilter(MTLS_PORT);

    private static MockHttpServletRequest req(String uri, int localPort) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", uri);
        r.setRequestURI(uri);
        r.setLocalPort(localPort);
        return r;
    }

    @Test
    void agentPathOnMtlsPort_passesThrough() throws Exception {
        MockHttpServletRequest request = req("/api/v1/endpoint-agent/endpoint-enrollments/auto", MTLS_PORT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("chain invoked").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void agentPathOnPlainPort_forbidden_andShortCircuits() throws Exception {
        MockHttpServletRequest request = req("/api/v1/endpoint-agent/endpoint-enrollments/auto", PLAIN_PORT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("controller must NOT be reached").isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(MtlsConnectorGuardFilter.ERR_MTLS_REQUIRED);
    }

    @Test
    void nonAgentPathOnMtlsPort_notFound_andShortCircuits() throws Exception {
        MockHttpServletRequest request = req("/api/v1/endpoint-admin/endpoint-devices", MTLS_PORT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("admin surface must NOT be served on mTLS port").isNull();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void nonAgentPathOnPlainPort_passesThrough() throws Exception {
        MockHttpServletRequest request = req("/api/v1/endpoint-admin/endpoint-devices", PLAIN_PORT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("normal admin traffic on plain port").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void actuatorOnPlainPort_passesThrough() throws Exception {
        MockHttpServletRequest request = req("/actuator/health", PLAIN_PORT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
