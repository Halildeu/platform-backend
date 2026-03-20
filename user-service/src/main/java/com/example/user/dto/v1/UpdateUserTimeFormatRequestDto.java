package com.example.user.dto.v1;

public class UpdateUserTimeFormatRequestDto {

    private String timeFormat;
    private Integer expectedVersion;
    private String source;
    private Integer attemptCount;
    private String queueActionId;

    public String getTimeFormat() {
        return timeFormat;
    }

    public void setTimeFormat(String timeFormat) {
        this.timeFormat = timeFormat;
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
