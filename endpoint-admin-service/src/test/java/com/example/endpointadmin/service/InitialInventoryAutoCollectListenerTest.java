package com.example.endpointadmin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** platform-backend#1206 — the listener must never turn an accepted heartbeat into an error. */
@ExtendWith(MockitoExtension.class)
class InitialInventoryAutoCollectListenerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();

    @Mock private InitialInventoryAutoCollectService service;
    @InjectMocks private InitialInventoryAutoCollectListener listener;

    @Test
    void asksTheServiceForACollectCapableDevice() {
        when(service.enqueueIfNeeded(TENANT, DEVICE)).thenReturn(Optional.of(UUID.randomUUID()));

        listener.onHeartbeatRecorded(new DeviceHeartbeatRecordedEvent(TENANT, DEVICE, true));

        verify(service).enqueueIfNeeded(TENANT, DEVICE);
    }

    @Test
    void ignoresAnAgentThatCannotCollect() {
        listener.onHeartbeatRecorded(new DeviceHeartbeatRecordedEvent(TENANT, DEVICE, false));

        verifyNoInteractions(service);
    }

    @Test
    void swallowsAFailureSoTheCommittedHeartbeatStaysAccepted() {
        when(service.enqueueIfNeeded(TENANT, DEVICE)).thenThrow(new CannotAcquireLockException("lock timeout"));

        assertThatCode(() -> listener.onHeartbeatRecorded(new DeviceHeartbeatRecordedEvent(TENANT, DEVICE, true)))
                .doesNotThrowAnyException();
    }
}
