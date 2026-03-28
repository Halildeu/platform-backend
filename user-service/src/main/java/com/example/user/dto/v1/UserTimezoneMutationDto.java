package com.example.user.dto.v1;

public class UserTimezoneMutationDto {
    private String status;
    private String auditId;
    private String resourceKey;
    private String timezone;
    private Integer version;
    private String source;
    private String message;
    private String errorCode;
    private String conflictReason;

    public UserTimezoneMutationDto() {
    }

    public UserTimezoneMutationDto(String status,
                                   String auditId,
                                   String resourceKey,
                                   String timezone,
                                   Integer version,
                                   String source,
                                   String message,
                                   String errorCode,
                                   String conflictReason) {
        this.status = status;
        this.auditId = auditId;
        this.resourceKey = resourceKey;
        this.timezone = timezone;
        this.version = version;
        this.source = source;
        this.message = message;
        this.errorCode = errorCode;
        this.conflictReason = conflictReason;
    }

    public static UserTimezoneMutationDto ok(String auditId,
                                             String resourceKey,
                                             String timezone,
                                             Integer version,
                                             String source) {
        return new UserTimezoneMutationDto(
                "ok",
                auditId,
                resourceKey,
                timezone,
                version,
                source,
                "User timezone synced.",
                null,
                null
        );
    }

    public static UserTimezoneMutationDto conflict(String auditId,
                                                   String resourceKey,
                                                   String timezone,
                                                   Integer version,
                                                   String source,
                                                   String message,
                                                   String errorCode,
                                                   String conflictReason) {
        return new UserTimezoneMutationDto(
                "conflict",
                auditId,
                resourceKey,
                timezone,
                version,
                source,
                message,
                errorCode,
                conflictReason
        );
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getConflictReason() {
        return conflictReason;
    }

    public void setConflictReason(String conflictReason) {
        this.conflictReason = conflictReason;
    }
}
