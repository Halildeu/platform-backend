package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.config.ViewOnlyAuthoritySecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ViewOnlyRequestBoundaryFilterTest {
    private static final String ROOT = "/api/v1/endpoint-admin/remote-access/preflight";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ViewOnlyRequestBoundaryFilter filter = new ViewOnlyRequestBoundaryFilter(mapper);

    @Test
    void rejectsAbsentLengthBeforeAuthenticationOrBodyAllocation() throws Exception {
        MockHttpServletRequest request = request(ROOT + "/checkpoint-leases/redeem", null, null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertExactFailure(response, 411, "CONTENT_LENGTH_REQUIRED");
    }

    @Test
    void rejectsOversizedAndNonJsonBodiesBeforeControllerAllocation() throws Exception {
        MockHttpServletRequest oversized = request(
                ROOT + "/checkpoints", "application/json",
                new byte[ViewOnlyCheckpointCreateVerifier.MAX_REQUEST_BYTES + 1]);
        MockHttpServletResponse oversizedResponse = new MockHttpServletResponse();
        filter.doFilter(oversized, oversizedResponse, new MockFilterChain());
        assertExactFailure(oversizedResponse, 413, "REQUEST_BODY_TOO_LARGE");

        MockHttpServletRequest wrongType = request(ROOT + "/attest", "text/plain", new byte[]{1});
        MockHttpServletResponse wrongTypeResponse = new MockHttpServletResponse();
        filter.doFilter(wrongType, wrongTypeResponse, new MockFilterChain());
        assertExactFailure(wrongTypeResponse, 415, "CONTENT_TYPE_UNSUPPORTED");
    }

    @Test
    void permitsOnlyBoundedJsonForKnownPostRoute() throws Exception {
        MockHttpServletRequest request = request(ROOT + "/attest", "application/json; charset=UTF-8",
                "{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void neverInterceptsAnUnrelatedProductPostRoute() throws Exception {
        MockHttpServletRequest request = request(
                "/api/v1/endpoint-admin/devices/commands", "application/json", new byte[]{1});
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void servletContainerAutoRegistrationIsDisabled() {
        ViewOnlyAuthoritySecurityConfig security = new ViewOnlyAuthoritySecurityConfig();

        assertThat(security.viewOnlyRequestBoundaryFilterRegistration(filter).isEnabled()).isFalse();
    }

    private static MockHttpServletRequest request(String uri, String contentType, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (body != null) {
            request.setContent(body);
        }
        return request;
    }

    private void assertExactFailure(
            MockHttpServletResponse response, int expectedStatus, String expectedCode) throws Exception {
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        Set<String> fields = StreamSupport.stream(
                        ((Iterable<String>) body::fieldNames).spliterator(), false)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder(
                "schemaVersion", "errorId", "code", "message", "retryable",
                "mutationCount", "credentialMaterialIncluded");
        assertThat(body.get("code").textValue()).isEqualTo(expectedCode);
        assertThat(body.get("mutationCount").intValue()).isZero();
        assertThat(body.get("credentialMaterialIncluded").booleanValue()).isFalse();
    }
}
