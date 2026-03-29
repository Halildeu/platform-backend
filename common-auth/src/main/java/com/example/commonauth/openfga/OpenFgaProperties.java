package com.example.commonauth.openfga;

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

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
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
