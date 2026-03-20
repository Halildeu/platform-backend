package com.example.report.access;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ReportDefinition;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

@Component
public class RowFilterInjector {

    public record RlsResult(String whereClause, MapSqlParameterSource params) {}

    public RlsResult buildRlsClause(ReportDefinition def, AuthzMeResponse authz) {
        if (authz == null) {
            return new RlsResult("1=0", new MapSqlParameterSource());
        }

        if (authz.isSuperAdmin()) {
            return new RlsResult(null, null);
        }

        if (def.access() == null || def.access().rowFilter() == null) {
            return new RlsResult(null, null);
        }

        AccessConfig.RowFilter rowFilter = def.access().rowFilter();

        if (rowFilter.bypassPermission() != null && authz.hasPermission(rowFilter.bypassPermission())) {
            return new RlsResult(null, null);
        }

        String scopeType = rowFilter.scopeType();
        String column = rowFilter.column();

        if (scopeType == null || column == null) {
            return new RlsResult(null, null);
        }

        Set<String> allowedIds = authz.getScopeRefIds(scopeType);
        if (allowedIds.isEmpty()) {
            return new RlsResult("1=0", new MapSqlParameterSource());
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("_rlsIds", allowedIds);
        String clause = "[" + column + "] IN (:_rlsIds)";

        return new RlsResult(clause, params);
    }
}
