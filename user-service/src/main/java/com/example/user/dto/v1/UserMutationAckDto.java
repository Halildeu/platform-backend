package com.example.user.dto.v1;

public class UserMutationAckDto {

    private String status;
    private String auditId;

    public UserMutationAckDto() {
    }

    public UserMutationAckDto(String status, String auditId) {
        this.status = status;
        this.auditId = auditId;
    }

    public static UserMutationAckDto ok(String auditId) {
        return new UserMutationAckDto("ok", auditId);
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
}
