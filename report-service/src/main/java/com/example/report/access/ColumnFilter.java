package com.example.report.access;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ColumnFilter {

    public List<String> getVisibleColumns(ReportDefinition def, AuthzMeResponse authz) {
        Set<String> hiddenColumns = getHiddenColumns(def, authz);
        return def.columns().stream()
                .map(ColumnDefinition::field)
                .filter(field -> !hiddenColumns.contains(field))
                .toList();
    }

    public List<ColumnDefinition> getVisibleColumnDefinitions(ReportDefinition def, AuthzMeResponse authz) {
        Set<String> hiddenColumns = getHiddenColumns(def, authz);
        return def.columns().stream()
                .filter(col -> !hiddenColumns.contains(col.field()))
                .toList();
    }

    private Set<String> getHiddenColumns(ReportDefinition def, AuthzMeResponse authz) {
        Set<String> hidden = new HashSet<>();

        if (authz == null || authz.isSuperAdmin() || def.access() == null) {
            return hidden;
        }

        Map<String, List<String>> restrictions = def.access().columnRestrictions();
        if (restrictions == null || restrictions.isEmpty()) {
            return hidden;
        }

        for (Map.Entry<String, List<String>> entry : restrictions.entrySet()) {
            String requiredPermission = entry.getKey();
            List<String> restrictedColumns = entry.getValue();

            if (!authz.hasPermission(requiredPermission)) {
                hidden.addAll(restrictedColumns);
            }
        }

        return hidden;
    }
}
