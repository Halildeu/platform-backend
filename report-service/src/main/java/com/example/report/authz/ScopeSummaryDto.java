package com.example.report.authz;

public class ScopeSummaryDto {
    private String scopeType;
    private String scopeRefId;

    public ScopeSummaryDto() {
    }

    public ScopeSummaryDto(String scopeType, String scopeRefId) {
        this.scopeType = scopeType;
        this.scopeRefId = scopeRefId;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeRefId() {
        return scopeRefId;
    }

    public void setScopeRefId(String scopeRefId) {
        this.scopeRefId = scopeRefId;
    }
}
