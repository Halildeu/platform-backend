package com.example.permission.service;

import com.example.permission.dto.v1.AuthzPermissionScopesDto;
import com.example.permission.dto.v1.AuthzScopeDto;
import com.example.permission.dto.v1.AuthzUserScopesResponseDto;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.model.UserPermissionScope;
import com.example.permission.repository.UserPermissionScopeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AuthorizationQueryService {

    private final UserPermissionScopeRepository userPermissionScopeRepository;

    public AuthorizationQueryService(UserPermissionScopeRepository userPermissionScopeRepository) {
        this.userPermissionScopeRepository = userPermissionScopeRepository;
    }

    @Transactional(readOnly = true)
    public AuthzUserScopesResponseDto getUserScopes(Long userId) {
        List<UserPermissionScope> rows = userPermissionScopeRepository.findByUserIdWithPermissionAndScope(userId);
        Map<String, Map<String, Set<Long>>> grouped = new LinkedHashMap<>();
        Set<String> permissionCodes = new LinkedHashSet<>();

        for (UserPermissionScope entry : rows) {
            String permissionCode = entry.getPermission().getCode();
            String scopeType = entry.getScope().getScopeType();
            Long scopeRefId = entry.getScope().getRefId();
            permissionCodes.add(permissionCode);

            grouped
                    .computeIfAbsent(permissionCode, key -> new LinkedHashMap<>())
                    .computeIfAbsent(scopeType, key -> new LinkedHashSet<>())
                    .add(scopeRefId);
        }

        List<AuthzPermissionScopesDto> items = new ArrayList<>();
        for (Map.Entry<String, Map<String, Set<Long>>> permissionEntry : grouped.entrySet()) {
            List<AuthzScopeDto> scopes = permissionEntry.getValue().entrySet().stream()
                    .map(entry -> new AuthzScopeDto(entry.getKey(), entry.getValue().stream().toList()))
                    .toList();
            items.add(new AuthzPermissionScopesDto(permissionEntry.getKey(), scopes));
        }

        List<ScopeSummaryDto> allowedScopes = rows.stream()
                .filter(e -> e.getScope() != null && e.getScope().getScopeType() != null && e.getScope().getRefId() != null)
                .map(e -> new ScopeSummaryDto(e.getScope().getScopeType(), e.getScope().getRefId()))
                .toList();
        boolean superAdmin = permissionCodes.stream().anyMatch(p -> p != null && p.equalsIgnoreCase("admin"));

        return new AuthzUserScopesResponseDto(items, items.size(), allowedScopes, superAdmin);
    }

    /**
     * Giriş yapmış kullanıcı için scope özetini döner. UI menü aç/kapa gibi senaryolar için basit biçim.
     */
    @Transactional(readOnly = true)
    public Map<String, Set<Long>> getUserScopeSummary(Long userId) {
        List<UserPermissionScope> rows = userPermissionScopeRepository.findByUserIdWithPermissionAndScope(userId);
        Map<String, Set<Long>> groupedByType = new LinkedHashMap<>();

        for (UserPermissionScope entry : rows) {
            if (entry.getScope() == null) {
                continue;
            }
            String scopeType = entry.getScope().getScopeType();
            Long scopeRefId = entry.getScope().getRefId();
            if (scopeType == null || scopeRefId == null) {
                continue;
            }
            groupedByType
                    .computeIfAbsent(scopeType, key -> new LinkedHashSet<>())
                    .add(scopeRefId);
        }
        return groupedByType;
    }
}
