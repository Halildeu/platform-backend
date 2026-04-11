package com.example.permission.event;

/**
 * Published when role permissions change. Handled after transaction commits
 * to avoid stale data in OpenFGA tuple sync.
 * CNS-20260411-002 #2-3: @TransactionalEventListener(AFTER_COMMIT).
 */
public record RoleChangeEvent(Long roleId) {}
