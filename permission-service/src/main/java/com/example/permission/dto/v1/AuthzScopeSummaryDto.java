package com.example.permission.dto.v1;

import java.util.List;

public class AuthzScopeSummaryDto {
    private String scopeType;
    private List<Long> refIds;

    public AuthzScopeSummaryDto() {
    }

    public AuthzScopeSummaryDto(String scopeType, List<Long> refIds) {
        this.scopeType = scopeType;
        this.refIds = refIds;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public List<Long> getRefIds() {
        return refIds;
    }


    public void setRefIds(List<Long> refIds) {
        this.refIds = refIds;
    }
}
