package com.example.budget.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class BudgetForbiddenObservationFilterTest {
    private final BudgetForbiddenObservationFilter filter =
            new BudgetForbiddenObservationFilter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsOnlySafeAuthorizationProjectionForProjectForbiddenResponse()
            throws Exception {
        Jwt jwt = Jwt.withTokenValue("not-logged")
                .header("alg", "none")
                .subject("subject-present")
                .claim("org_id", "tenant-present")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(
                        new SimpleGrantedAuthority("SCOPE_budget:read"),
                        new SimpleGrantedAuthority("SCOPE_budget:write"))));

        Logger logger =
                (Logger) LoggerFactory.getLogger(BudgetForbiddenObservationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request =
                    new MockHttpServletRequest(
                            "GET", "/api/v1/budgets/projects/bindings");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(
                    request,
                    response,
                    (servletRequest, servletResponse) ->
                            ((MockHttpServletResponse) servletResponse).setStatus(403));

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .isEqualTo(
                            "budget_request_forbidden method=GET route=project_bindings "
                                    + "scopeRead=true scopeWrite=true scopeApprove=false "
                                    + "rolePlanner=false roleApprover=false subjectPresent=true "
                                    + "tenantClaimPresent=false orgClaimPresent=true");
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .doesNotContain("not-logged", "subject-present", "tenant-present");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void ignoresForbiddenResponsesOutsideProjectBudgetRoutes() throws Exception {
        Jwt jwt = Jwt.withTokenValue("not-logged")
                .header("alg", "none")
                .subject("subject-present")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt));

        Logger logger =
                (Logger) LoggerFactory.getLogger(BudgetForbiddenObservationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/v1/budgets");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(
                    request,
                    response,
                    (servletRequest, servletResponse) ->
                            ((MockHttpServletResponse) servletResponse).setStatus(403));

            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void ignoresSuccessfulResponsesInsideProjectBudgetRoutes() throws Exception {
        Jwt jwt = Jwt.withTokenValue("not-logged")
                .header("alg", "none")
                .subject("subject-present")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt));

        Logger logger =
                (Logger) LoggerFactory.getLogger(BudgetForbiddenObservationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request =
                    new MockHttpServletRequest(
                            "GET", "/api/v1/budgets/projects/bindings");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(
                    request,
                    response,
                    (servletRequest, servletResponse) ->
                            ((MockHttpServletResponse) servletResponse).setStatus(200));

            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void ignoresForbiddenProjectResponsesWithoutJwtPrincipal() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "anonymousUser",
                        "not-logged",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        Logger logger =
                (Logger) LoggerFactory.getLogger(BudgetForbiddenObservationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request =
                    new MockHttpServletRequest(
                            "GET", "/api/v1/budgets/projects/bindings");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(
                    request,
                    response,
                    (servletRequest, servletResponse) ->
                            ((MockHttpServletResponse) servletResponse).setStatus(403));

            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void mapsProjectRoutesToLowCardinalityCategories() {
        assertThat(BudgetForbiddenObservationFilter.routeCategory(
                        "/api/v1/budgets/projects/7c3b/actuals/sync"))
                .isEqualTo("project_actuals_sync");
        assertThat(BudgetForbiddenObservationFilter.routeCategory(
                        "/api/v1/budgets/projects/7c3b/actuals/summary"))
                .isEqualTo("project_actuals_summary");
        assertThat(BudgetForbiddenObservationFilter.routeCategory(
                        "/api/v1/budgets/projects/7c3b/actuals"))
                .isEqualTo("project_actuals_rows");
        assertThat(BudgetForbiddenObservationFilter.routeCategory(
                        "/api/v1/budgets/projects/unexpected"))
                .isEqualTo("project_other");
    }
}
