package com.example.permission.dto.access;

public class CloneRoleRequest {
    private Long sourceRoleId;
    private String name;
    private String description;
    private Boolean copyMemberCount;
    private Long performedBy; // audit için isteğe bağlı

    public Long getSourceRoleId() { return sourceRoleId; }
    public void setSourceRoleId(Long sourceRoleId) { this.sourceRoleId = sourceRoleId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getCopyMemberCount() { return copyMemberCount; }
    public void setCopyMemberCount(Boolean copyMemberCount) { this.copyMemberCount = copyMemberCount; }

    public Long getPerformedBy() { return performedBy; }
    public void setPerformedBy(Long performedBy) { this.performedBy = performedBy; }
}

