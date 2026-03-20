package com.example.user.dto.v1;

public class UserLocaleSnapshotDto {

    private String resourceKey;
    private String locale;
    private Integer version;

    public UserLocaleSnapshotDto() {
    }

    public UserLocaleSnapshotDto(String resourceKey, String locale, Integer version) {
        this.resourceKey = resourceKey;
        this.locale = locale;
        this.version = version;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
