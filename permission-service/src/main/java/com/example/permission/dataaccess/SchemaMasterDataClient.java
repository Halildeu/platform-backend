package com.example.permission.dataaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Codex 019dda1c iter-29: schema-service master-data RestClient. Replaces
 * the empty-Postgres path in {@link MasterDataService} as the primary
 * source-of-truth for scope-picker entity lists. workcube_mikrolink
 * tables in reports_db are never populated by ETL today, so going through
 * schema-service (which already holds the live MSSQL connection used by
 * SchemaExtractService) gets the drawer the ~35 our_company / N projects /
 * etc. that the source database actually has.
 *
 * <p>Failure mode: any timeout / 5xx / 4xx / network error returns an
 * empty list. The {@link com.example.permission.controller.MasterDataController}
 * proxy then falls back to the legacy {@link MasterDataService} (Postgres
 * mirror) — which today returns empty too, but the contract preserves
 * the option of mirror filling in the future.
 *
 * <p>Auth: schema-service requires an internal API key on the
 * {@code X-Internal-Api-Key} header. The key value comes from a Vault
 * entry mounted as {@code SCHEMA_INTERNAL_API_KEY}; an empty value means
 * the deployment hasn't been configured for live MSSQL yet, in which
 * case the client short-circuits to empty so the fallback path runs.
 */
@Component
public class SchemaMasterDataClient {

    private static final Logger log = LoggerFactory.getLogger(SchemaMasterDataClient.class);

    private final RestClient client;
    private final String apiKey;
    private final boolean configured;

    public SchemaMasterDataClient(
            RestClient.Builder restClientBuilder,
            @Value("${permission.master-data.schema-service-base-url:http://schema-service:8096}") String baseUrl,
            @Value("${permission.master-data.schema-service-api-key:}") String apiKey,
            @Value("${permission.master-data.timeout-ms:2500}") int timeoutMs) {
        this.client = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
                    setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
                }})
                .build();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.configured = !this.apiKey.isBlank();
        if (!this.configured) {
            log.info("SchemaMasterDataClient disabled — SCHEMA_INTERNAL_API_KEY not set; "
                    + "MasterDataController will fall back to reports_db (Postgres mirror).");
        } else {
            log.info("SchemaMasterDataClient configured against {} (timeout={}ms)", baseUrl, timeoutMs);
        }
    }

    public List<MasterDataItem> list(String kind) {
        if (!configured) return Collections.emptyList();
        try {
            List<MasterDataItem> body = client.get()
                    .uri("/api/v1/schema/master-data/{kind}", kind)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header("X-Internal-Api-Key", apiKey)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<MasterDataItem>>() {});
            return body != null ? body : Collections.emptyList();
        } catch (RestClientResponseException ex) {
            log.warn("SchemaMasterDataClient {} returned {} {}: {}",
                    kind, ex.getStatusCode(), ex.getStatusText(), ex.getResponseBodyAsString());
            return Collections.emptyList();
        } catch (ResourceAccessException ex) {
            log.warn("SchemaMasterDataClient {} unreachable: {}", kind, ex.getMessage());
            return Collections.emptyList();
        } catch (Exception ex) {
            log.warn("SchemaMasterDataClient {} unexpected error: {}", kind, ex.toString());
            return Collections.emptyList();
        }
    }

    public boolean isConfigured() {
        return configured;
    }
}
