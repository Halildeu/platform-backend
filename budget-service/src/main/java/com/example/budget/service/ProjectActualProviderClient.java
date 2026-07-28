package com.example.budget.service;

import static com.example.budget.api.ProjectActualDtos.ProviderActualPage;

import java.time.LocalDate;

public interface ProjectActualProviderClient {
    ProviderActualPage fetch(
            String authorization,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            String cursor,
            int limit);
}
