package com.example.endpointadmin.model;

/**
 * Failure classification for the rollout failed-device queue (Faz 22.5 #527,
 * contract §3). Wire + DB value == the Java name (UPPER_SNAKE), so
 * {@code @Enumerated(EnumType.STRING)} is faithful and no converter is needed
 * (contrast {@link RolloutFailureState} / {@link ClassificationConfidence},
 * whose wire values are lower-case).
 *
 * <p>The 6-value set is fixed by the contract schema
 * ({@code failed-device-queue.schema.json $defs.failureClass}); a drift test
 * pins this enum to that enum.
 */
public enum RolloutFailureClass {
    DNS_EDGE_MTLS,
    CERT_IDENTITY,
    INSTALLER_MSI,
    SERVICE_HMAC_MODE,
    BACKEND_RESULT_SUBMIT,
    EDR_NETWORK
}
