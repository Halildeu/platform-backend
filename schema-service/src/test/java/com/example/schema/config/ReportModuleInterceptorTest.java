package com.example.schema.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * gitops#3608 — JWT callers are gated with their own token; JWT-less in-cluster
 * callers keep their controller-side internal-key guard.
 */
class ReportModuleInterceptorTest {

    private static final String TOKEN = "eyJ.raw.token";

    private ReportModuleAccessGate gate;
    private ReportModuleInterceptor interceptor;
    private HandlerMethod handler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() throws Exception {
        gate = mock(ReportModuleAccessGate.class);
        interceptor = new ReportModuleInterceptor(gate);
        Method m = Sample.class.getDeclaredMethod("tables");
        handler = new HandlerMethod(new Sample(), m);
        request = new MockHttpServletRequest("GET", "/api/v1/schema/tables");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowedCallerPassesThroughAndTheGateSawTheRawToken() throws Exception {
        authenticate(jwt());
        when(gate.decide(TOKEN)).thenReturn(new ReportModuleAccessGate.Decision(true, "module_view"));

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(gate).decide(TOKEN);
    }

    @Test
    void deniedCallerGets403WithReason() throws Exception {
        authenticate(jwt());
        when(gate.decide(TOKEN)).thenReturn(new ReportModuleAccessGate.Decision(false, "no_report_module"));

        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"forbidden\"")
                .contains("\"reason\":\"no_report_module\"");
    }

    @Test
    void jwtLessCallerIsLeftToTheControllerGuard() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        verify(gate, never()).decide(any());
    }

    @Test
    void unauthenticatedTokenInContextIsNotTrusted() throws Exception {
        // single-arg JwtAuthenticationToken leaves isAuthenticated()=false — treated as no principal
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt()));

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        verify(gate, never()).decide(any());
    }

    @Test
    void nonHandlerMethodsAreIgnored() throws Exception {
        authenticate(jwt());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(gate, never()).decide(any());
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue(TOKEN).header("alg", "none").subject("keycloak-uuid").build();
    }

    private static void authenticate(Jwt jwt) {
        // The authorities variant is what the resource-server filter installs and the
        // only one that marks the token authenticated.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    static class Sample {
        @SuppressWarnings("unused")
        public String tables() {
            return "ok";
        }
    }
}
