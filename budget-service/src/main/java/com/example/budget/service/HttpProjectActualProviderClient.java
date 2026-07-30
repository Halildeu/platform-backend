package com.example.budget.service;

import static com.example.budget.api.ProjectActualDtos.ProviderActualPage;
import static com.example.budget.api.ProjectActualDtos.ProviderSourceLinePage;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpProjectActualProviderClient implements ProjectActualProviderClient {
    private final RestClient client;

    public HttpProjectActualProviderClient(
            RestClient.Builder builder,
            @Value("${budget.actual-provider.base-url:http://report-service:8080}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public ProviderActualPage fetch(
            String authorization,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            String cursor,
            int limit) {
        return client.get()
                .uri(uri -> {
                    var builder = uri.path("/api/v1/reports/project-actuals/provider")
                            .queryParam("projectId", projectId)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("limit", limit);
                    if (cursor != null && !cursor.isBlank()) {
                        builder.queryParam("cursor", cursor);
                    }
                    return builder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("X-Company-Id", Long.toString(companyId))
                .retrieve()
                .body(ProviderActualPage.class);
    }

    @Override
    public ProviderSourceLinePage fetchSourceLines(
            String authorization,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            String cursor,
            int limit) {
        return client.get()
                .uri(uri -> {
                    var builder = uri.path("/api/v1/reports/project-actuals/provider/source-lines")
                            .queryParam("projectId", projectId)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("limit", limit);
                    if (cursor != null && !cursor.isBlank()) {
                        builder.queryParam("cursor", cursor);
                    }
                    return builder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("X-Company-Id", Long.toString(companyId))
                .retrieve()
                .body(ProviderSourceLinePage.class);
    }
}
