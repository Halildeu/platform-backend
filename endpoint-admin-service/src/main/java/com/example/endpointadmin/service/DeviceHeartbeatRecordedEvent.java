package com.example.endpointadmin.service;

import java.util.UUID;

/**
 * Published inside {@link EndpointHeartbeatService#recordHeartbeat}'s
 * transaction once the heartbeat row is flushed. Consumed AFTER_COMMIT by
 * {@link InitialInventoryAutoCollectListener} (platform-backend#1206). Carries
 * only stable identifiers plus what the heartbeat itself advertised, so the
 * listener never touches a detached entity.
 *
 * @param tenantId                tenant/org scope of the device
 * @param deviceId                the device that heartbeated
 * @param collectInventoryCapable the heartbeat advertised the
 *                                {@code COLLECT_INVENTORY} capability
 */
public record DeviceHeartbeatRecordedEvent(UUID tenantId,
                                           UUID deviceId,
                                           boolean collectInventoryCapable) {
}
