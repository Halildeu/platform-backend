package com.example.user.dto.v1;

public class UserTimeFormatSnapshotDto {

    private String resourceKey;
    private String timeFormat;
    private Integer version;

    public UserTimeFormatSnapshotDto() {
    }

    public UserTimeFormatSnapshotDto(String resourceKey, String timeFormat, Integer version) {
        this.resourceKey = resourceKey;
        this.timeFormat = timeFormat;
        this.version = version;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String getTimeFormat() {
        return timeFormat;
    }

    public void setTimeFormat(String timeFormat) {
        this.timeFormat = timeFormat;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
