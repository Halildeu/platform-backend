package com.example.endpointadmin.model;

/**
 * Wave-12 PR-5 — kind of policy change a {@link PolicyChangeApproval}
 * proposes. Carried as {@code PolicyApprovalDomainExtras.changeKind} on
 * the platform-web side.
 */
public enum PolicyChangeKind {
    CREATE,
    UPDATE,
    DELETE
}
