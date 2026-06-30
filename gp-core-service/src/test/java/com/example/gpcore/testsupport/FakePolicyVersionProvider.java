package com.example.gpcore.testsupport;

import com.example.gpcore.port.PolicyVersionProvider;

/**
 * Mutable {@link PolicyVersionProvider} for cache-invalidation tests: bumping
 * {@code tupleRevision} / {@code policyVersion} must invalidate cached positives.
 * Can also be made to throw / return null to exercise the version-unavailable
 * fail-closed path.
 */
public class FakePolicyVersionProvider implements PolicyVersionProvider {

    private volatile String policyVersion = "policy-v1";
    private volatile String tupleRevision = "tuple-r1";
    private volatile boolean throwOnAccess = false;
    private volatile boolean nullValues = false;

    public FakePolicyVersionProvider policyVersion(String v) {
        this.policyVersion = v;
        return this;
    }

    public FakePolicyVersionProvider tupleRevision(String v) {
        this.tupleRevision = v;
        return this;
    }

    public FakePolicyVersionProvider throwOnAccess(boolean v) {
        this.throwOnAccess = v;
        return this;
    }

    public FakePolicyVersionProvider nullValues(boolean v) {
        this.nullValues = v;
        return this;
    }

    @Override
    public String policyVersion() {
        if (throwOnAccess) {
            throw new RuntimeException("injected version failure");
        }
        return nullValues ? null : policyVersion;
    }

    @Override
    public String tupleRevision() {
        if (throwOnAccess) {
            throw new RuntimeException("injected version failure");
        }
        return nullValues ? null : tupleRevision;
    }
}
