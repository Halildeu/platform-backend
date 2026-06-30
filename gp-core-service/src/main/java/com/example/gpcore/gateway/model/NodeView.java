package com.example.gpcore.gateway.model;

import com.example.gpcore.domain.NodeRef;

import java.util.Map;
import java.util.Objects;

/** A visible node returned by the Read Gateway (content surfaced only after an allow decision). */
public record NodeView(NodeRef ref, Map<String, Object> attributes) {

    public NodeView {
        Objects.requireNonNull(ref, "ref");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
