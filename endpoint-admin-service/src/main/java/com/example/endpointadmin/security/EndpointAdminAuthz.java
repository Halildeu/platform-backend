package com.example.endpointadmin.security;

/**
 * Endpoint admin authz constants.
 *
 * Faz 22.1.1 BE-009 — A1.1-prime relation alignment (Codex 019ded8d AGREE):
 * Mevcut OpenFGA model {@code module} type'ı {@code can_view} + {@code can_manage}
 * relation'larını destekliyor; önceki {@code "viewer"} + {@code "manager"} string
 * literal'leri model'de tanımsızdı (tuple seed sırasında relation_not_found error
 * dönerdi). 22.1.1 acceptance scope module-level can_view/can_manage; ADR-0012-EA
 * scope/action types (endpoint, policy, command, inventory, audit + can_assign,
 * can_execute, can_signoff, can_revoke) ileri sprint (BE-009b veya 22.2/22.3).
 *
 * Tuple shape: {@code user:<id># <relation> @module:ENDPOINT_ADMIN}
 */
public final class EndpointAdminAuthz {

    /** Module object ID — uppercase application contract (kept stable across PR). */
    public static final String MODULE = "ENDPOINT_ADMIN";

    /** Read-only access relation, OpenFGA model {@code module#can_view}. */
    public static final String VIEWER = "can_view";

    /** Mutation/management relation, OpenFGA model {@code module#can_manage}. */
    public static final String MANAGER = "can_manage";

    private EndpointAdminAuthz() {
    }
}
