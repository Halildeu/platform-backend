package com.example.user.dto.v1;

public class UserTimezoneSnapshotDto {
    private String resourceKey;
    private String timezone;
    private Integer version;

    public UserTimezoneSnapshotDto() {
    }

    public UserTimezoneSnapshotDto(String resourceKey, String timezone, Integer version) {
        this.resourceKey = resourceKey;
        this.timezone = timezone;
        this.version = version;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
