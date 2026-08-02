package com.example.commonauth.openfga;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * OpenFGA configuration properties.
 * Bind with prefix "erp.openfga" in application.yml.
 */
public class OpenFgaProperties {

    private boolean enabled = false;
    private String apiUrl = "http://localhost:4000";
    private String storeId;
    private String modelId;

    /** TTL in seconds for check result cache. Default: 10s. Set to 0 to disable caching. */
    private int checkCacheTtlSeconds = 10;

    /**
     * Bounds on a single authorization round trip.
     *
     * <p>Without these the SDK inherits the JDK default, which is no timeout at all, and a
     * request that reaches an unreachable OpenFGA over an already-established socket waits
     * forever. Measured on 2026-08-02 (ES-308 chaos drill, platform-k8s-gitops#2667): with
     * the authz plane cut, an Etik Speak staff request never returned and the edge gave up
     * with a 504 after 90 seconds. The gate itself held — no case was disclosed — but the
     * caller learned that from a gateway error 90 seconds later instead of a prompt denial.
     *
     * <p>The defaults are deliberately generous rather than tight. Every caller treats a
     * failed check as a DENY, so a timeout that fires early does not merely slow someone
     * down — it locks out a legitimate user. Anything finite beats infinity here; one second
     * would be its own outage. Tune per service via {@code erp.openfga.connect-timeout} /
     * {@code erp.openfga.read-timeout} if a deployment needs different bounds.
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    private Duration readTimeout = Duration.ofSeconds(10);

    /** Dev/permitAll mode: fallback scope when OpenFGA is disabled. */
    private DevScope devScope = new DevScope();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public int getCheckCacheTtlSeconds() {
        return checkCacheTtlSeconds;
    }

    public void setCheckCacheTtlSeconds(int checkCacheTtlSeconds) {
        this.checkCacheTtlSeconds = checkCacheTtlSeconds;
    }

    public DevScope getDevScope() {
        return devScope;
    }

    public void setDevScope(DevScope devScope) {
        this.devScope = devScope;
    }

    public static class DevScope {
        private Set<Long> companyIds = Set.of(1L);
        private Set<Long> projectIds = Set.of(1L);
        private Set<Long> warehouseIds = Set.of(1L);
        private boolean superAdmin = false;

        public Set<Long> getCompanyIds() {
            return companyIds;
        }

        public void setCompanyIds(Set<Long> companyIds) {
            this.companyIds = companyIds;
        }

        public Set<Long> getProjectIds() {
            return projectIds;
        }

        public void setProjectIds(Set<Long> projectIds) {
            this.projectIds = projectIds;
        }

        public Set<Long> getWarehouseIds() {
            return warehouseIds;
        }

        public void setWarehouseIds(Set<Long> warehouseIds) {
            this.warehouseIds = warehouseIds;
        }

        public boolean isSuperAdmin() {
            return superAdmin;
        }

        public void setSuperAdmin(boolean superAdmin) {
            this.superAdmin = superAdmin;
        }
    }
}
