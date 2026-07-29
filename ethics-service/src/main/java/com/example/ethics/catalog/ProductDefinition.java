package com.example.ethics.catalog;

import java.util.Set;

/**
 * ES-403 — a sellable product: a name and the capabilities it carries.
 *
 * <p>Defined in code rather than a table for the first slice. A catalog row that can be
 * edited at runtime is a way to change what a customer is entitled to without a review, and
 * the subscriptions that reference these definitions are the part that belongs in a database.
 * The definitions themselves move with a deployment, which is the audit trail.
 */
public record ProductDefinition(String id, Set<EthicsCapability> capabilities) {

    public ProductDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("product definition requires an id");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public boolean carries(EthicsCapability capability) {
        return capabilities.contains(capability);
    }
}
