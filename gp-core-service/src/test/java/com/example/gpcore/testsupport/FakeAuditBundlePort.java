package com.example.gpcore.testsupport;

import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.gateway.model.AuditItem;
import com.example.gpcore.port.content.AuditBundlePort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link AuditBundlePort} that counts {@code items(scope)} calls — so
 * tests can assert the port is NEVER queried for a hidden scope (Codex 019f1913 #10).
 */
public class FakeAuditBundlePort implements AuditBundlePort {

    private final Map<NodeRef, List<AuditItem>> byScope = new HashMap<>();
    private final AtomicInteger itemCalls = new AtomicInteger();

    public FakeAuditBundlePort items(NodeRef scope, List<AuditItem> items) {
        byScope.put(scope, items);
        return this;
    }

    public int itemCalls() {
        return itemCalls.get();
    }

    @Override
    public List<AuditItem> items(NodeRef scope) {
        itemCalls.incrementAndGet();
        return byScope.getOrDefault(scope, List.of());
    }
}
