package com.example.endpointadmin.model;

/** #527 failed-device rollout queue — failure class (mirrors the contract JSON schema enum). */
public enum RolloutFailureClass {
    DNS_EDGE_MTLS, CERT_IDENTITY, INSTALLER_MSI, SERVICE_HMAC_MODE, BACKEND_RESULT_SUBMIT, EDR_NETWORK
}
