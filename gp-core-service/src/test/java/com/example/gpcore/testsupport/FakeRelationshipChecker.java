package com.example.gpcore.testsupport;

import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.port.RelationshipChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic in-memory {@link RelationshipChecker}. Models OpenFGA tuples as
 * explicit grants; supports revocation, invocation counting (for stale-positive
 * assertions) and fault injection (exception / short batch) for fail-closed tests.
 */
public class FakeRelationshipChecker implements RelationshipChecker {

    private final java.util.Set<String> grants = ConcurrentHashMap.newKeySet();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger batchItemCalls = new AtomicInteger();
    private volatile boolean throwOnCheck = false;
    private volatile boolean throwOnBatch = false;
    private volatile boolean shortBatch = false;

    public FakeRelationshipChecker grant(String userId, String relation, NodeRef ref) {
        grants.add(key(userId, relation, ref));
        return this;
    }

    public FakeRelationshipChecker revoke(String userId, String relation, NodeRef ref) {
        grants.remove(key(userId, relation, ref));
        return this;
    }

    public int callCount() {
        return calls.get();
    }

    public int batchItemCount() {
        return batchItemCalls.get();
    }

    public FakeRelationshipChecker throwOnCheck(boolean v) {
        this.throwOnCheck = v;
        return this;
    }

    public FakeRelationshipChecker throwOnBatch(boolean v) {
        this.throwOnBatch = v;
        return this;
    }

    /** When true, the batch response is one element short — exercises fail-closed padding. */
    public FakeRelationshipChecker shortBatch(boolean v) {
        this.shortBatch = v;
        return this;
    }

    @Override
    public boolean canRelate(Principal principal, String relation, NodeRef ref) {
        calls.incrementAndGet();
        if (throwOnCheck) {
            throw new RuntimeException("injected check failure");
        }
        return grants.contains(key(principal.userId(), relation, ref));
    }

    @Override
    public List<Boolean> canRelateBatch(Principal principal, List<RelationRequest> requests) {
        if (throwOnBatch) {
            throw new RuntimeException("injected batch failure");
        }
        List<Boolean> out = new ArrayList<>();
        int n = shortBatch ? Math.max(0, requests.size() - 1) : requests.size();
        for (int i = 0; i < n; i++) {
            batchItemCalls.incrementAndGet();
            RelationRequest r = requests.get(i);
            out.add(grants.contains(key(principal.userId(), r.relation(), r.ref())));
        }
        return out;
    }

    private static String key(String userId, String relation, NodeRef ref) {
        return userId + "|" + relation + "|" + ref.type() + ":" + ref.id();
    }
}
