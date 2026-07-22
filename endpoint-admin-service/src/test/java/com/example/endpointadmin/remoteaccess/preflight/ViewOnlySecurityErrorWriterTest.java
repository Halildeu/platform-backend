package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;

class ViewOnlySecurityErrorWriterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ViewOnlySecurityErrorWriter writer = new ViewOnlySecurityErrorWriter(mapper);

    @Test
    void authenticationFailureUsesStrictOidcErrorWithoutExceptionDetail() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.commence(new MockHttpServletRequest(), response,
                new BadCredentialsException("raw bearer must never appear"));

        var body = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(body.get("code").textValue()).isEqualTo("OIDC_INVALID");
        assertThat(body.toString()).doesNotContain("raw bearer");
        assertThat(body.get("credentialMaterialIncluded").booleanValue()).isFalse();
    }

    @Test
    void accessDeniedUsesStrictClaimMismatch() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        var body = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("code").textValue()).isEqualTo("OIDC_CLAIM_MISMATCH");
    }
}
