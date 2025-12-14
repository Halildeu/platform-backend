package com.example.permission.service;

import com.example.permission.dto.v1.AuthzUserScopesResponseDto;
import com.example.permission.model.Permission;
import com.example.permission.model.Scope;
import com.example.permission.model.UserPermissionScope;
import com.example.permission.repository.UserPermissionScopeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationQueryServiceTest {

    @Mock
    private UserPermissionScopeRepository userPermissionScopeRepository;

    @InjectMocks
    private AuthorizationQueryService authorizationQueryService;

    @Test
    void shouldReturnAllowedScopesAndSuperAdminFlag() {
        UserPermissionScope ups = new UserPermissionScope();
        Permission perm = new Permission();
        perm.setCode("admin");
        ups.setPermission(perm);
        Scope scope = new Scope();
        scope.setScopeType("COMPANY");
        scope.setRefId(101L);
        ups.setScope(scope);

        when(userPermissionScopeRepository.findByUserIdWithPermissionAndScope(1L))
                .thenReturn(List.of(ups));

        AuthzUserScopesResponseDto dto = authorizationQueryService.getUserScopes(1L);

        assertThat(dto.isSuperAdmin()).isTrue();
        assertThat(dto.getAllowedScopes())
                .extracting("scopeType", "scopeRefId")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("COMPANY", 101L));
    }
}
