package com.example.commonauth.scope;

/**
 * Temporary shim for services without DB access to authz_sync_version.
 * Returns 0 — cache relies purely on TTL.
 * Replace with RemoteAuthzVersionProvider in P1.
 */
public class ConstantAuthzVersionProvider implements AuthzVersionProvider {
    @Override
    public long getCurrentVersion() { return 0L; }
}
