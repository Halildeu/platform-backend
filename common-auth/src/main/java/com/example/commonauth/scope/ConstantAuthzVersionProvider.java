package com.example.commonauth.scope;

/**
 * @deprecated P1-A: Use {@link RemoteAuthzVersionProvider} instead.
 * Kept for test/fallback scenarios only.
 */
@Deprecated
public class ConstantAuthzVersionProvider implements AuthzVersionProvider {
    @Override
    public long getCurrentVersion() { return 0L; }
}
