package com.example.variant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

public class VariantResponse {

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

    public VariantResponse(UUID id, String gridId, String name, boolean isDefault, boolean isGlobal,
                           boolean isGlobalDefault, JsonNode state, Integer schemaVersion, boolean isCompatible,
                           Integer sortOrder, LocalDateTime createdAt, LocalDateTime updatedAt,
                           boolean isUserDefault, boolean isUserSelected) {
        this.id = id;
        this.gridId = gridId;
        this.name = name;
        this.isDefault = isDefault;
        this.isGlobal = isGlobal;
        this.isGlobalDefault = isGlobalDefault;
        this.state = state;
        this.schemaVersion = schemaVersion;
        this.isCompatible = isCompatible;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.isUserDefault = isUserDefault;
        this.isUserSelected = isUserSelected;
    }

    public UUID getId() {
        return id;
    }

    public String getGridId() {
        return gridId;
    }

    public String getName() {
        return name;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public boolean isGlobal() {
        return isGlobal;
    }

    public boolean isGlobalDefault() {
        return isGlobalDefault;
    }

    public JsonNode getState() {
        return state;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public boolean isCompatible() {
        return isCompatible;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isUserDefault() {
        return isUserDefault;
    }

    public boolean isUserSelected() {
        return isUserSelected;
    }
}
