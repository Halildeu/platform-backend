package com.example.user.dto.v1;

public class UserTimeFormatMutationDto {

    private String status;
    private String auditId;
    private String resourceKey;
    private String timeFormat;
    private Integer version;
    private String source;
    private String message;
    private String errorCode;
    private String conflictReason;

    public UserTimeFormatMutationDto() {
    }

    public UserTimeFormatMutationDto(String status,
                                     String auditId,
                                     String resourceKey,
                                     String timeFormat,
                                     Integer version,
                                     String source,
                                     String message,
                                     String errorCode,
                                     String conflictReason) {
        this.status = status;
        this.auditId = auditId;
        this.resourceKey = resourceKey;
        this.timeFormat = timeFormat;
        this.version = version;
        this.source = source;
        this.message = message;
        this.errorCode = errorCode;
        this.conflictReason = conflictReason;
    }

    public static UserTimeFormatMutationDto ok(String auditId,
                                               String resourceKey,
                                               String timeFormat,
                                               Integer version,
                                               String source) {
        return new UserTimeFormatMutationDto(
                "ok",
                auditId,
                resourceKey,
                timeFormat,
                version,
                source,
                "User time format synced.",
                null,
                null
        );
    }

    public static UserTimeFormatMutationDto conflict(String auditId,
                                                     String resourceKey,
                                                     String timeFormat,
                                                     Integer version,
                                                     String source,
                                                     String message,
                                                     String errorCode,
                                                     String conflictReason) {
        return new UserTimeFormatMutationDto(
                "conflict",
                auditId,
                resourceKey,
                timeFormat,
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
