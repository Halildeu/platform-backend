package com.example.permission.dto.v1;

public class RolePolicyDto {
    private String moduleKey;
    private String moduleLabel;
    private String level;
    private String lastUpdatedAt;
    private String updatedBy;

    public String getModuleKey() {
        return moduleKey;
    }

    public void setModuleKey(String moduleKey) {
        this.moduleKey = moduleKey;
    }

    public String getModuleLabel() {
        return moduleLabel;
    }

    public void setModuleLabel(String moduleLabel) {
        this.moduleLabel = moduleLabel;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
