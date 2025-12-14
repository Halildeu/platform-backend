package com.example.variant.dto.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

public class VariantDto {
    private UUID id;
    private String gridId;
    private String name;
    @JsonProperty("isDefault")
    private boolean isDefault;
    @JsonProperty("isGlobal")
    private boolean isGlobal;
    @JsonProperty("isGlobalDefault")
    private boolean isGlobalDefault;
    private JsonNode state;
    private Integer schemaVersion;
    private boolean isCompatible;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @JsonProperty("isUserDefault")
    private boolean isUserDefault;
    @JsonProperty("isUserSelected")
    private boolean isUserSelected;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getGridId() {
        return gridId;
    }

    public void setGridId(String gridId) {
        this.gridId = gridId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public boolean isGlobal() {
        return isGlobal;
    }

    public void setGlobal(boolean global) {
        isGlobal = global;
    }

    public boolean isGlobalDefault() {
        return isGlobalDefault;
    }

    public void setGlobalDefault(boolean globalDefault) {
        isGlobalDefault = globalDefault;
    }

    public JsonNode getState() {
        return state;
    }

    public void setState(JsonNode state) {
        this.state = state;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public boolean isCompatible() {
        return isCompatible;
    }

    public void setCompatible(boolean compatible) {
        isCompatible = compatible;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isUserDefault() {
        return isUserDefault;
    }

    public void setUserDefault(boolean userDefault) {
        isUserDefault = userDefault;
    }

    public boolean isUserSelected() {
        return isUserSelected;
    }

    public void setUserSelected(boolean userSelected) {
        isUserSelected = userSelected;
    }
}
