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
 * gitops#3608 — JWT callers are module-gated; JWT-less in-cluster callers keep
 * their controller-side internal-key guard; a JWT without a numeric user id is
 * refused, not guessed.
 */
class ReportModuleInterceptorTest {

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
    void allowedUserPassesThrough() throws Exception {
        authenticate(jwt("42"));
        when(gate.decide("42")).thenReturn(new ReportModuleAccessGate.Decision(true, "module_grant"));

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(gate).decide("42");
    }

    @Test
    void deniedUserGets403WithReason() throws Exception {
        authenticate(jwt("42"));
        when(gate.decide("42")).thenReturn(new ReportModuleAccessGate.Decision(false, "no_report_grant"));

        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"forbidden\"")
                .contains("\"reason\":\"no_report_grant\"");
    }

    @Test
    void jwtWithoutNumericUserIdIsRefusedNotGuessedFromSubject() throws Exception {
        authenticate(Jwt.withTokenValue("t").header("alg", "none").subject("keycloak-uuid").build());
        when(gate.decide(null)).thenReturn(new ReportModuleAccessGate.Decision(false, "no_user_id"));

        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        verify(gate).decide(null);
    }

    @Test
    void numericClaimShapesAreNormalised() {
        assertThat(ReportModuleInterceptor.numericUserId(jwt("42"))).isEqualTo("42");
        assertThat(ReportModuleInterceptor.numericUserId(
                Jwt.withTokenValue("t").header("alg", "none").subject("s").claim("userId", 7L).build()))
                .isEqualTo("7");
        assertThat(ReportModuleInterceptor.numericUserId(
                Jwt.withTokenValue("t").header("alg", "none").subject("s").claim("uid", " 9 ").build()))
                .isEqualTo("9");
        assertThat(ReportModuleInterceptor.numericUserId(
                Jwt.withTokenValue("t").header("alg", "none").subject("s").claim("userId", "someone@example").build()))
                .isNull();
    }

    @Test
    void jwtLessCallerIsLeftToTheControllerGuard() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        verify(gate, never()).decide(any());
    }

    @Test
    void nonHandlerMethodsAreIgnored() throws Exception {
        authenticate(jwt("42"));

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(gate, never()).decide(any());
    }

    private static Jwt jwt(String userId) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("s").claim("userId", userId).build();
    }

    private static void authenticate(Jwt jwt) {
        // The authorities variant is what the resource-server filter installs and the
        // only one that marks the token authenticated; the single-arg one leaves
        // isAuthenticated()=false and would make the interceptor skip the caller.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    static class Sample {
        @SuppressWarnings("unused")
        public String tables() {
            return "ok";
        }
    }
}
