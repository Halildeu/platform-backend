package com.example.permission.dto;

public class MutationAckResponse {
    private String status;
    private String auditId;

    public MutationAckResponse() {}

    public MutationAckResponse(String status, String auditId) {
        this.status = status;
        this.auditId = auditId;
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

