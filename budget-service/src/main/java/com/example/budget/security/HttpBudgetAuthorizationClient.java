package com.example.budget.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class HttpBudgetAuthorizationClient implements BudgetAuthorizationClient {
    private final RestClient client;

    public HttpBudgetAuthorizationClient(
            RestClient.Builder builder,
            @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public AuthorizationSnapshot fetch(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Bearer token is required");
        }
        try {
            AuthzMeResponse response = client.get()
                    .uri("/api/v1/authz/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(AuthzMeResponse.class);
            if (response == null || response.userId() == null || response.userId().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Authorization service returned an invalid identity snapshot");
            }
            Set<Long> companies = new LinkedHashSet<>();
            Set<Long> projects = new LinkedHashSet<>();
            if (response.allowedScopes() != null) {
                for (Scope scope : response.allowedScopes()) {
                    if (scope == null || scope.scopeType() == null || scope.scopeRefId() == null) {
                        continue;
                    }
                    if ("COMPANY".equalsIgnoreCase(scope.scopeType())) {
                        companies.add(scope.scopeRefId());
                    } else if ("PROJECT".equalsIgnoreCase(scope.scopeType())) {
                        projects.add(scope.scopeRefId());
                    }
                }
            }
            return new AuthorizationSnapshot(
                    response.userId(), companies, projects, Boolean.TRUE.equals(response.superAdmin()));
        } catch (HttpClientErrorException.Unauthorized rejected) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Authorization service rejected a token accepted by budget-service",
                    rejected);
        } catch (HttpClientErrorException.Forbidden denied) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Account is not authorized", denied);
        } catch (HttpClientErrorException.NotFound missing) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Authorized user profile is unavailable", missing);
        } catch (HttpServerErrorException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Authorization service is temporarily unavailable",
                    unavailable);
        } catch (ResponseStatusException typed) {
            throw typed;
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Authorization service is temporarily unavailable",
                    unavailable);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthzMeResponse(
            String userId,
            List<Scope> allowedScopes,
            Boolean superAdmin) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Scope(String scopeType, Long scopeRefId) {
    }
}
