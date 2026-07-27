package com.example.budget.security;

public record BudgetActor(String tenantId, long companyId, String subject) {
}
