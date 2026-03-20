package com.example.user.dto.v1;

public class UserSessionTimeoutSnapshotDto {

    private String resourceKey;
    private Integer sessionTimeoutMinutes;
    private Integer version;

    public UserSessionTimeoutSnapshotDto() {
    }

    public UserSessionTimeoutSnapshotDto(String resourceKey, Integer sessionTimeoutMinutes, Integer version) {
        this.resourceKey = resourceKey;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        this.version = version;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
