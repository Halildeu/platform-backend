package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContextCache;
import com.example.permission.model.AuthzSyncVersion;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.AuthzSyncVersionRepository;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthzVersionIntegrationTest {

    @Mock OpenFgaAuthzService authzService;
    @Mock RolePermissionRepository rolePermissionRepository;
    @Mock UserRoleAssignmentRepository assignmentRepository;
    @Mock AuthzSyncVersionRepository versionRepository;
    @Mock ScopeContextCache scopeContextCache;

    AuthzVersionService versionService;
    TupleSyncService tupleSyncService;

    @BeforeEach
    void setUp() {
        versionService = new AuthzVersionService(versionRepository);
        tupleSyncService = new TupleSyncService(
                authzService, rolePermissionRepository, assignmentRepository,
                versionService, scopeContextCache);
    }

    @Test
    @DisplayName("syncFeatureTuplesForUser increments version once and evicts user")
    void syncFeatureIncrementsOnce() {
        when(versionRepository.incrementVersion()).thenReturn(1);
        AuthzSyncVersion bumped = new AuthzSyncVersion();
        bumped.setVersion(2L);
        when(versionRepository.findById(1)).thenReturn(Optional.of(bumped));

        tupleSyncService.syncFeatureTuplesForUser("10", List.of());

        verify(versionRepository, times(1)).incrementVersion();
        verify(scopeContextCache).evictUser("10");
    }

    @Test
    @DisplayName("syncFeatureTuplesForUser with skipVersionIncrement=true does NOT increment")
    void syncFeatureSkipIncrement() {
        tupleSyncService.syncFeatureTuplesForUser("10", List.of(), true);

        verify(versionRepository, never()).incrementVersion();
        verify(scopeContextCache, never()).evictUser(any());
    }

    @Test
    @DisplayName("propagateRoleChange increments once (not N times)")
    void propagateRoleChangeSingleIncrement() {
        when(assignmentRepository.findByRoleIdAndActiveTrue(1L)).thenReturn(List.of());
        when(versionRepository.incrementVersion()).thenReturn(1);
        AuthzSyncVersion bumped = new AuthzSyncVersion();
        bumped.setVersion(2L);
        when(versionRepository.findById(1)).thenReturn(Optional.of(bumped));

        tupleSyncService.propagateRoleChange(1L);

        verify(versionRepository, times(1)).incrementVersion();
        verify(scopeContextCache).evictAll();
    }

    @Test
    @DisplayName("syncScopeTuples increments version and evicts user")
    void syncScopeIncrementsAndEvicts() {
        when(versionRepository.incrementVersion()).thenReturn(1);
        AuthzSyncVersion bumped = new AuthzSyncVersion();
        bumped.setVersion(2L);
        when(versionRepository.findById(1)).thenReturn(Optional.of(bumped));

        tupleSyncService.syncScopeTuples("10", List.of(1L), List.of(), List.of(), List.of());

        verify(versionRepository, times(1)).incrementVersion();
        verify(scopeContextCache).evictUser("10");
    }

    @Test
    @DisplayName("syncScopeTuples with skipVersionIncrement=true skips")
    void syncScopeSkipIncrement() {
        tupleSyncService.syncScopeTuples("10", List.of(1L), List.of(), List.of(), List.of(), true);

        verify(versionRepository, never()).incrementVersion();
        verify(scopeContextCache, never()).evictUser(any());
    }
}
