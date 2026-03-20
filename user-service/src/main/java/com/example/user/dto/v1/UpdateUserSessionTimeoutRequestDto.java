package com.example.user.dto.v1;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class UpdateUserSessionTimeoutRequestDto {

    @NotNull
    @Min(1)
    @Max(1440)
    private Integer sessionTimeoutMinutes;

    @NotNull
    @Min(0)
    private Integer expectedVersion;

    private String source;
    @Min(1)
    private Integer attemptCount;
    private String queueActionId;

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
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
