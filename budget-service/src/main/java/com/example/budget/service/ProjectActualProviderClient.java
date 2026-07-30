package com.example.budget.service;

import static com.example.budget.api.ProjectActualDtos.ProviderActualPage;
import static com.example.budget.api.ProjectActualDtos.ProviderSourceLinePage;

import java.time.LocalDate;
import java.util.List;

public interface ProjectActualProviderClient {
    ProviderActualPage fetch(
            String authorization,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            String cursor,
            int limit);

    default ProviderSourceLinePage fetchSourceLines(
            String authorization,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            String cursor,
            int limit) {
        return new ProviderSourceLinePage(List.of(), null, false);
    }
}
