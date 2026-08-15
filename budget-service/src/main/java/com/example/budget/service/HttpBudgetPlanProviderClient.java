package com.example.budget.service;

import static com.example.budget.api.WorkcubePlanImportDtos.ProviderBudgetPlanPage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpBudgetPlanProviderClient implements BudgetPlanProviderClient {
    private final RestClient client;

    public HttpBudgetPlanProviderClient(
            RestClient.Builder builder,
            @Value("${budget.actual-provider.base-url:http://report-service:8080}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public ProviderBudgetPlanPage fetchPlans(
            String authorization,
            long companyId,
            int fiscalYear,
            String cursor,
            int limit) {
        return client.get()
                .uri(uri -> {
                    var builder = uri.path("/api/v1/reports/budget-plans/provider")
                            .queryParam("fiscalYear", fiscalYear)
                            .queryParam("limit", limit);
                    if (cursor != null && !cursor.isBlank()) {
                        builder.queryParam("cursor", cursor);
                    }
                    return builder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("X-Company-Id", Long.toString(companyId))
                .retrieve()
                .body(ProviderBudgetPlanPage.class);
    }
}
