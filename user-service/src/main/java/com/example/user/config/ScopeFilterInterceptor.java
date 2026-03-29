package com.example.user.config;

import com.example.commonauth.scope.RlsScopeHelper;
import com.example.commonauth.scope.ScopeContext;
import com.example.commonauth.scope.ScopeContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Enables Hibernate company scope filter AND PostgreSQL RLS on every request.
 * Reads allowed company IDs from ScopeContext (set by ScopeContextFilter).
 * SuperAdmin users bypass both filters.
 */
@Component
public class ScopeFilterInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ScopeFilterInterceptor.class);

    private final EntityManager entityManager;
    private final DataSource dataSource;

    public ScopeFilterInterceptor(EntityManager entityManager, DataSource dataSource) {
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ScopeContext ctx = ScopeContextHolder.get();
        if (ctx == null) {
            log.trace("No ScopeContext — filters not applied (permitAll or health check)");
            return true;
        }

        // Hibernate @Filter
        enableHibernateFilter(ctx);

        // PostgreSQL RLS session variables
        applyRlsScope(ctx);

        return true;
    }

    private void enableHibernateFilter(ScopeContext ctx) {
        if (ctx.superAdmin()) {
            log.debug("SuperAdmin — Hibernate filter bypassed for user {}", ctx.userId());
            return;
        }
        if (ctx.allowedCompanyIds().isEmpty()) {
            log.warn("User {} has empty company scope — queries will return no data", ctx.userId());
            return;
        }
        try {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("companyScope")
                    .setParameterList("companyIds", ctx.allowedCompanyIds().stream().toList());
            log.debug("Hibernate filter enabled: user={}, companies={}", ctx.userId(), ctx.allowedCompanyIds());
        } catch (Exception e) {
            log.error("Failed to enable Hibernate filter for user {}", ctx.userId(), e);
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
