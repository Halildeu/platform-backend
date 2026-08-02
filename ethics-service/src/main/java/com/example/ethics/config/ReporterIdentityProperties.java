package com.example.ethics.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ES-212 — keys for the reporter-identity envelope.
 *
 * <p>{@code keys} is a map of key id to base64 AES-256 material, supplied by an
 * ExternalSecret from Vault; {@code activeKeyId} selects the one new rows are written
 * with. Old ids stay in the map so rows written under a retired key remain readable —
 * rotation adds a key, it does not invalidate what was already sealed.
 *
 * <p>Everything defaults to empty, which means identity collection is inoperable until an
 * overlay supplies material. That default matters: a deployment that enables CONFIDENTIAL
 * in policy but forgets the key must refuse the report, not fall back to storing the
 * person in the clear.
 */
@ConfigurationProperties(prefix = "ethics.identity")
public class ReporterIdentityProperties {

    private String activeKeyId = "";
    private Map<String, String> keys = new LinkedHashMap<>();

    public String getActiveKeyId() { return activeKeyId; }
    public void setActiveKeyId(String activeKeyId) { this.activeKeyId = activeKeyId; }
    public Map<String, String> getKeys() { return keys; }
    public void setKeys(Map<String, String> keys) { this.keys = keys; }
}
