package com.example.permission.model;

/**
 * Types of permission granules in the Zanzibar authorization model.
 * A role is a permission package containing entries of these types.
 */
public enum PermissionType {
    MODULE,
    ACTION,
    REPORT,

    /** @deprecated P1-A removed page/field from Zanzibar model. Kept for Hibernate backward compat with existing DB rows. */
    @Deprecated PAGE,
    /** @deprecated P1-A removed page/field from Zanzibar model. Kept for Hibernate backward compat with existing DB rows. */
    @Deprecated FIELD
}
