package com.example.commonauth.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches authz version from permission-service via lightweight HTTP GET.
 * Local memo cache avoids hitting the network on every request.
 * Fail-open: returns last known version on error.
 */
public class RemoteAuthzVersionProvider implements AuthzVersionProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteAuthzVersionProvider.class);

    private final String versionUrl;
    private final long memoTtlMs;
    private final HttpClient httpClient;

    private volatile long cachedVersion = 0L;
    private volatile long cachedAt = 0L;

    public RemoteAuthzVersionProvider(String versionUrl, long memoTtlMs) {
        this.versionUrl = versionUrl;
        this.memoTtlMs = memoTtlMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public long getCurrentVersion() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < memoTtlMs) {
            return cachedVersion;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(versionUrl))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                // Parse {"authzVersion": 42}
                int idx = body.indexOf("authzVersion");
                if (idx >= 0) {
                    int colonIdx = body.indexOf(':', idx);
                    int endIdx = body.indexOf('}', colonIdx);
                    String numStr = body.substring(colonIdx + 1, endIdx).trim();
                    cachedVersion = Long.parseLong(numStr);
                    cachedAt = now;
                }
            }
        } catch (Exception e) {
            log.debug("RemoteAuthzVersionProvider fetch failed ({}), returning cached={}", e.getMessage(), cachedVersion);
        }
        return cachedVersion;
    }
}
