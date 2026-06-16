package com.example.meeting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.RequireModule;
import com.example.meeting.security.MeetingAuthz;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;

/**
 * Unit tests for {@link MeetingRequireModuleInterceptor} — Faz 24 (#410).
 * Proves the OpenFGA module gate using the canonical {@link MeetingAuthz}
 * constants ({@code module:meeting} + {@code can_view}/{@code can_manage}):
 * fail-open when disabled, 403 on deny, 401 when no principal, allow on
 * grant.
 */
class MeetingRequireModuleInterceptorTest {

    private final OpenFgaAuthzService authzService = mock(OpenFgaAuthzService.class);
    private final MeetingRequireModuleInterceptor interceptor =
            new MeetingRequireModuleInterceptor(authzService);

    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    static final class GuardedHandler {
        @SuppressWarnings("unused")
        public void mutate() {
        }
    }

    private HandlerMethod guardedHandler() throws NoSuchMethodException {
        Method method = GuardedHandler.class.getMethod("mutate");
        return new HandlerMethod(new GuardedHandler(), method);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("sub", subject)
                .build();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                jwt, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void failsOpen_whenOpenFgaDisabled() throws Exception {
        when(authzService.isEnabled()).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new MockHttpServletRequest(), response, guardedHandler());

        assertThat(proceed).isTrue();
        verify(authzService, never()).check(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void allows_whenCheckGrants() throws Exception {
        when(authzService.isEnabled()).thenReturn(true);
        authenticateAs("user-123");
        when(authzService.check(eq("user-123"), eq(MeetingAuthz.MANAGER), eq("module"), eq(MeetingAuthz.MODULE)))
                .thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new MockHttpServletRequest(), response, guardedHandler());

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void denies403_whenCheckRejects() throws Exception {
        when(authzService.isEnabled()).thenReturn(true);
        authenticateAs("user-123");
        when(authzService.check(eq("user-123"), eq(MeetingAuthz.MANAGER), eq("module"), eq(MeetingAuthz.MODULE)))
                .thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new MockHttpServletRequest(), response, guardedHandler());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void denies401_whenNoPrincipal() throws Exception {
        when(authzService.isEnabled()).thenReturn(true);
        // No authentication in context.
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new MockHttpServletRequest(), response, guardedHandler());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(authzService, never()).check(anyString(), anyString(), anyString(), anyString());
    }
}
