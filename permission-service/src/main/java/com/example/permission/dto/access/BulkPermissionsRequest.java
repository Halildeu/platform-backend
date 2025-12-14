package com.example.permission.dto.access;

import java.util.List;

public class BulkPermissionsRequest {
    private List<Long> roleIds;
    private String moduleKey;
    private String moduleLabel;
    private String level; // NONE|VIEW|EDIT|MANAGE
    private Long performedBy; // audit için isteğe bağlı

    public List<Long> getRoleIds() { return roleIds; }
    public void setRoleIds(List<Long> roleIds) { this.roleIds = roleIds; }

    public String getModuleKey() { return moduleKey; }
    public void setModuleKey(String moduleKey) { this.moduleKey = moduleKey; }

    public String getModuleLabel() { return moduleLabel; }
    public void setModuleLabel(String moduleLabel) { this.moduleLabel = moduleLabel; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public Long getPerformedBy() { return performedBy; }
    public void setPerformedBy(Long performedBy) { this.performedBy = performedBy; }
}

