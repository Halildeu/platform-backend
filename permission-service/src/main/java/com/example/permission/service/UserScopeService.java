package com.example.permission.service;

import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.model.Scope;
import com.example.permission.model.UserPermissionScope;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.ScopeRepository;
import com.example.permission.repository.UserPermissionScopeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserScopeService {

    private static final Set<String> ALLOWED_SCOPE_TYPES = Set.of("COMPANY", "PROJECT");

    private final UserPermissionScopeRepository userPermissionScopeRepository;
    private final ScopeRepository scopeRepository;
    private final PermissionRepository permissionRepository;

    public UserScopeService(UserPermissionScopeRepository userPermissionScopeRepository,
                            ScopeRepository scopeRepository,
                            PermissionRepository permissionRepository) {
        this.userPermissionScopeRepository = userPermissionScopeRepository;
        this.scopeRepository = scopeRepository;
        this.permissionRepository = permissionRepository;
    }

    @Transactional(readOnly = true)
    public List<ScopeSummaryDto> listUserScopes(Long userId) {
        return userPermissionScopeRepository.findByUserIdWithPermissionAndScope(userId).stream()
                .filter(ups -> ups.getScope() != null && ups.getScope().getScopeType() != null && ups.getScope().getRefId() != null)
                .map(ups -> new ScopeSummaryDto(ups.getScope().getScopeType(), ups.getScope().getRefId()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void addScope(Long userId, String scopeType, Long scopeRefId, String permissionCode) {
        validateScope(scopeType);
        var permission = permissionRepository.findByCodeIgnoreCase(permissionCode)
                .orElseThrow(() -> new IllegalArgumentException("Permission not found: " + permissionCode));

        Scope scope = scopeRepository.findByScopeTypeIgnoreCaseAndRefId(scopeType, scopeRefId)
                .orElseGet(() -> {
                    Scope s = new Scope();
                    s.setScopeType(scopeType.toUpperCase());
                    s.setRefId(scopeRefId);
                    return scopeRepository.save(s);
                });

        boolean exists = userPermissionScopeRepository.existsByUserIdAndPermission_CodeIgnoreCaseAndScope_Id(
                userId, permissionCode, scope.getId());
        if (exists) {
            return; // idempotent
        }

        UserPermissionScope ups = new UserPermissionScope();
        ups.setUserId(userId);
        ups.setPermission(permission);
        ups.setScope(scope);
        userPermissionScopeRepository.save(ups);
    }

    @Transactional
    public void removeScope(Long userId, String scopeType, Long scopeRefId, String permissionCode) {
        validateScope(scopeType);
        permissionRepository.findByCodeIgnoreCase(permissionCode)
                .orElseThrow(() -> new IllegalArgumentException("Permission not found: " + permissionCode));

        scopeRepository.findByScopeTypeIgnoreCaseAndRefId(scopeType, scopeRefId).ifPresent(scope ->
                userPermissionScopeRepository.deleteByUserIdAndPermission_CodeIgnoreCaseAndScope_Id(userId, permissionCode, scope.getId())
        );
    }

    private void validateScope(String scopeType) {
        if (scopeType == null || !ALLOWED_SCOPE_TYPES.contains(scopeType.toUpperCase())) {
            throw new IllegalArgumentException("Desteklenmeyen scopeType: " + scopeType);
        }
    }
}
