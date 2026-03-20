package com.example.user.dto.v1;

public class NotificationPreferenceMutationDto {

    private String status;
    private String auditId;
    private String resourceKey;
    private String channel;
    private boolean enabled;
    private String frequency;
    private int version;
    private String source;
    private String message;
    private String errorCode;
    private String conflictReason;

    public static NotificationPreferenceMutationDto ok(String auditId,
                                                       String resourceKey,
                                                       String channel,
                                                       boolean enabled,
                                                       String frequency,
                                                       int version,
                                                       String source) {
        NotificationPreferenceMutationDto dto = new NotificationPreferenceMutationDto();
        dto.setStatus("ok");
        dto.setAuditId(auditId);
        dto.setResourceKey(resourceKey);
        dto.setChannel(channel);
        dto.setEnabled(enabled);
        dto.setFrequency(frequency);
        dto.setVersion(version);
        dto.setSource(source);
        dto.setMessage("Notification preference synced.");
        dto.setErrorCode(null);
        dto.setConflictReason(null);
        return dto;
    }

    public static NotificationPreferenceMutationDto conflict(String auditId,
                                                             String resourceKey,
                                                             String channel,
                                                             boolean enabled,
                                                             String frequency,
                                                             int version,
                                                             String source,
                                                             String message,
                                                             String errorCode,
                                                             String conflictReason) {
        NotificationPreferenceMutationDto dto = new NotificationPreferenceMutationDto();
        dto.setStatus("conflict");
        dto.setAuditId(auditId);
        dto.setResourceKey(resourceKey);
        dto.setChannel(channel);
        dto.setEnabled(enabled);
        dto.setFrequency(frequency);
        dto.setVersion(version);
        dto.setSource(source);
        dto.setMessage(message);
        dto.setErrorCode(errorCode);
        dto.setConflictReason(conflictReason);
        return dto;
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

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
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
