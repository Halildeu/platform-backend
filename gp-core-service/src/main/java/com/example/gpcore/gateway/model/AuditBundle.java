package com.example.gpcore.gateway.model;

import com.example.gpcore.domain.NodeRef;

import java.util.List;
import java.util.Objects;

/**
 * An exportable audit bundle: ONLY items the caller is authorized to EXPORT. The
 * scope is authorized FIRST; a hidden scope yields an empty bundle and the
 * underlying port is never queried (Codex 019f1913 #10).
 */
public record AuditBundle(NodeRef scope, List<AuditItem> items) {

    public AuditBundle {
        Objects.requireNonNull(scope, "scope");
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static AuditBundle empty(NodeRef scope) {
        return new AuditBundle(scope, List.of());
    }
}
