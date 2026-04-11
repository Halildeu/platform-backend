package com.example.permission.model;

/**
 * Types of permission granules in the Zanzibar authorization model.
 * A role is a permission package containing entries of these types.
 *
 * PAGE and FIELD removed in V10 migration (TB-21).
 */
public enum PermissionType {
    MODULE,
    ACTION,
    REPORT
}
