package com.example.permission.service;

import com.example.permission.model.AuthzSyncVersion;
import com.example.permission.repository.AuthzSyncVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthzVersionServiceTest {

    @Mock AuthzSyncVersionRepository repository;
    @InjectMocks AuthzVersionService service;

    @Test @DisplayName("getCurrentVersion returns seeded value")
    void getCurrentVersion() {
        when(repository.findById(1)).thenReturn(Optional.of(new AuthzSyncVersion()));
        assertEquals(1L, service.getCurrentVersion());
    }

    @Test @DisplayName("getCurrentVersion returns 0 when missing")
    void getCurrentVersionMissing() {
        when(repository.findById(1)).thenReturn(Optional.empty());
        assertEquals(0L, service.getCurrentVersion());
    }

    @Test @DisplayName("incrementVersion bumps and returns new value")
    void incrementVersion() {
        when(repository.incrementVersion()).thenReturn(1);
        AuthzSyncVersion bumped = new AuthzSyncVersion();
        bumped.setVersion(2L);
        when(repository.findById(1)).thenReturn(Optional.of(bumped));
        assertEquals(2L, service.incrementVersion());
        verify(repository).incrementVersion();
    }

    @Test @DisplayName("incrementVersion seeds when missing")
    void incrementVersionSeeds() {
        when(repository.incrementVersion()).thenReturn(0);
        when(repository.save(any())).thenReturn(new AuthzSyncVersion());
        assertEquals(2L, service.incrementVersion());
        verify(repository).save(any());
    }
}
