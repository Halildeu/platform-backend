package com.example.endpointadmin.model;

/**
 * BE-021 — snapshot of the install-preflight decision at command issue
 * time. Only PASS / WARN can become an audit row; BLOCK preflights
 * never reach command creation (the dedicated install endpoint returns
 * 409 instead of creating the command).
 */
public enum InstallPreflightDecisionRecorded {
    PASS,
    WARN
}
