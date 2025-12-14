package com.example.permission.dto.v1;

import java.util.List;

public class AuthzScopeDto {
    private String type;
    private List<Long> ids;

    public AuthzScopeDto() {
    }

    public AuthzScopeDto(String type, List<Long> ids) {
        this.type = type;
        this.ids = ids;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<Long> getIds() {
        return ids;
    }

    public void setIds(List<Long> ids) {
        this.ids = ids;
    }
}
