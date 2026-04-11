package com.example.permission.config;

import com.example.commonauth.scope.RlsScopeHelper;
import com.example.commonauth.scope.ScopeContext;
import com.example.commonauth.scope.ScopeContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enables Hibernate scope filters AND PostgreSQL RLS on every request.
 * Handles both companyScope (for UserRoleAssignment), scopeCompanyFilter (for Scope),
 * and userScope (for UserPermissionScope) filters.
 */
@Component
@ConditionalOnBean(jakarta.persistence.EntityManagerFactory.class)
public class ScopeFilterInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ScopeFilterInterceptor.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ScopeContext ctx = ScopeContextHolder.get();
        if (ctx == null) {
            log.trace("No ScopeContext — filters not applied (permitAll or health check)");
            return true;
        }

        enableHibernateFilters(ctx);
        applyRlsScope(ctx);

        return true;
    }

    private void enableHibernateFilters(ScopeContext ctx) {
        if (ctx.superAdmin()) {
            log.debug("SuperAdmin — Hibernate filters bypassed for user {}", ctx.userId());
            return;
        }

        try {
            Session session = entityManager.unwrap(Session.class);

            // companyScope filter (UserRoleAssignment entity)
            if (!ctx.allowedCompanyIds().isEmpty()) {
                session.enableFilter("companyScope")
                        .setParameterList("companyIds", ctx.allowedCompanyIds().stream().toList());
                // scopeCompanyFilter (Scope entity — different name to avoid FilterDef conflict)
                session.enableFilter("scopeCompanyFilter")
                        .setParameterList("companyIds", ctx.allowedCompanyIds().stream().toList());
                log.debug("Hibernate companyScope + scopeCompanyFilter enabled: user={}, companies={}",
                        ctx.userId(), ctx.allowedCompanyIds());
            }

            // userScope filter (UserPermissionScope entity)
            if (ctx.userId() != null && !ctx.userId().isBlank()) {
                try {
                    Long userIdLong = Long.parseLong(ctx.userId());
                    session.enableFilter("userScope")
                            .setParameter("userId", userIdLong);
                    log.debug("Hibernate userScope filter enabled: user={}", ctx.userId());
                } catch (NumberFormatException e) {
                    log.warn("Cannot parse userId '{}' as Long for userScope filter", ctx.userId());
                }
            }
        } catch (Exception e) {
            log.error("Failed to enable Hibernate filters for user {}", ctx.userId(), e);
        }
    }

    private void applyRlsScope(ScopeContext ctx) {
        try {
            Session session = entityManager.unwrap(Session.class);
            session.doWork(connection -> RlsScopeHelper.applyScope(connection, ctx));
        } catch (Exception e) {
            log.error("Failed to apply RLS scope for user {}", ctx.userId(), e);
        }
    }
}
