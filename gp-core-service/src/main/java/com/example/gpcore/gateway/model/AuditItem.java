package com.example.gpcore.gateway.model;

import com.example.gpcore.domain.NodeRef;

import java.util.Objects;

/** A single item in an audit/export bundle, scoped to a node. */
public record AuditItem(NodeRef ref, String eventType, String detail) {

    public AuditItem {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(eventType, "eventType");
    }
}
