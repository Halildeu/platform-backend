package com.example.permission.dto.v1;

public class PermissionCatalogItemDto {
    private Long id;
    private String code;
    private String moduleKey;
    private String moduleLabel;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getModuleKey() {
        return moduleKey;
    }

    public void setModuleKey(String moduleKey) {
        this.moduleKey = moduleKey;
    }

    public String getModuleLabel() {
        return moduleLabel;
    }

    public void setModuleLabel(String moduleLabel) {
        this.moduleLabel = moduleLabel;
    }
}
