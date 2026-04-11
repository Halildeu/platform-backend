package com.example.variant.config;

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
 * Enables Hibernate visibilityScope filter AND PostgreSQL RLS on every request.
 * Handles the multi-branch visibility logic for VariantVisibility entity.
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

            // visibilityScope filter (VariantVisibility entity)
            if (ctx.userId() != null && !ctx.allowedCompanyIds().isEmpty()) {
                session.enableFilter("visibilityScope")
                        .setParameterList("companyIds", ctx.allowedCompanyIds().stream().toList())
                        .setParameter("userId", ctx.userId());
                log.debug("Hibernate visibilityScope filter enabled: user={}, companies={}",
                        ctx.userId(), ctx.allowedCompanyIds());
            } else if (ctx.userId() != null) {
                // User has no companies but still needs user-level visibility
                session.enableFilter("visibilityScope")
                        .setParameterList("companyIds", java.util.List.of(0L))
                        .setParameter("userId", ctx.userId());
                log.debug("Hibernate visibilityScope filter enabled (no companies): user={}", ctx.userId());
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
