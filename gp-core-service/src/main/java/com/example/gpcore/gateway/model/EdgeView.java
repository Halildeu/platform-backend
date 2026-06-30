package com.example.gpcore.gateway.model;

import com.example.gpcore.domain.NodeRef;

import java.util.Objects;

/** A visible edge returned by the Read Gateway (both endpoints + scope passed edge-visibility). */
public record EdgeView(String edgeType, NodeRef source, NodeRef target, NodeRef scope) {

    public EdgeView {
        Objects.requireNonNull(edgeType, "edgeType");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }
}
