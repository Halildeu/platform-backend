package com.example.user.dto.v1;

public class UserDateFormatSnapshotDto {

    private String resourceKey;
    private String dateFormat;
    private Integer version;

    public UserDateFormatSnapshotDto() {
    }

    public UserDateFormatSnapshotDto(String resourceKey, String dateFormat, Integer version) {
        this.resourceKey = resourceKey;
        this.dateFormat = dateFormat;
        this.version = version;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat) {
        this.dateFormat = dateFormat;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
