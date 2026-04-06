package com.example.permission.model;

/**
 * Grant types for role permissions.
 * DENY always wins over ALLOW when multiple roles are combined (deny-wins semantics).
 */
public enum GrantType {
    MANAGE,
    VIEW,
    ALLOW,
    DENY
}
