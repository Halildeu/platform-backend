package com.example.user.dto.v1;

public class UpdateUserLocaleRequestDto {

    private String locale;
    private Integer expectedVersion;
    private String source;
    private Integer attemptCount;
    private String queueActionId;

    public UpdateUserLocaleRequestDto() {
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getQueueActionId() {
        return queueActionId;
    }

    public void setQueueActionId(String queueActionId) {
        this.queueActionId = queueActionId;
    }
}
