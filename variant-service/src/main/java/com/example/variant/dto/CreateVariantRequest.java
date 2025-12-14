package com.example.variant.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateVariantRequest {

    @NotBlank
    private String gridId;

    @NotBlank
    private String name;

    @JsonProperty("isDefault")
    @JsonAlias("default")
    private boolean isDefault;

    @JsonProperty("isGlobal")
    @JsonAlias("global")
    private boolean isGlobal;

    @JsonProperty("isGlobalDefault")
    @JsonAlias({"globalDefault"})
    private boolean isGlobalDefault;

    @NotNull
    private JsonNode state;

    @NotNull
    private Integer schemaVersion;

    private Integer sortOrder;

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

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
