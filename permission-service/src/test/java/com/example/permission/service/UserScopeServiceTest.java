package com.example.permission.service;

import com.example.permission.model.Permission;
import com.example.permission.model.Scope;
import com.example.permission.model.UserPermissionScope;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.ScopeRepository;
import com.example.permission.repository.UserPermissionScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserScopeServiceTest {

    @Mock
    private UserPermissionScopeRepository userPermissionScopeRepository;
    @Mock
    private ScopeRepository scopeRepository;
    @Mock
    private PermissionRepository permissionRepository;

    @InjectMocks
    private UserScopeService userScopeService;

    private Permission permission;

    @BeforeEach
    void setUp() {
        permission = new Permission();
        permission.setCode("permission-scope-manage");
    }

    @Test
    void addScope_shouldBeIdempotent() {
        when(permissionRepository.findByCodeIgnoreCase("permission-scope-manage")).thenReturn(Optional.of(permission));
        Scope scope = new Scope();
        scope.setId(10L);
        scope.setScopeType("COMPANY");
        scope.setRefId(101L);
        when(scopeRepository.findByScopeTypeIgnoreCaseAndRefId("COMPANY", 101L)).thenReturn(Optional.of(scope));
        when(userPermissionScopeRepository.existsByUserIdAndPermission_CodeIgnoreCaseAndScope_Id(1L, "permission-scope-manage", 10L))
                .thenReturn(true);

        assertThatCode(() -> userScopeService.addScope(1L, "COMPANY", 101L, "permission-scope-manage"))
                .doesNotThrowAnyException();

        verify(userPermissionScopeRepository, never()).save(any(UserPermissionScope.class));
    }
}
