package com.example.gpcore.domain;

import java.util.Objects;

/**
 * A typed reference to a graph node (ontology v2 {@code (type, id)}). This is a
 * structural identifier only — it carries no node content/attributes, so it can
 * be evaluated for visibility BEFORE any content is read (Codex 019f1913 #4:
 * full node/content read must not happen before the relationship + policy
 * decision).
 *
 * @param type ontology node type (e.g. {@code control_instance}, {@code evidence_object})
 * @param id   stable node id within the type
 */
public record NodeRef(String type, String id) {

    public NodeRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("NodeRef type and id must not be blank");
        }
    }

    public static NodeRef of(String type, String id) {
        return new NodeRef(type, id);
    }

    /** OpenFGA object literal, e.g. {@code control_instance:42}. */
    public String toObjectLiteral() {
        return type + ":" + id;
    }
}
