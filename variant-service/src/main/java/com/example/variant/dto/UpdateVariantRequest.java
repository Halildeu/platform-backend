package com.example.variant.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public class UpdateVariantRequest {

    private String name;

    @JsonProperty("isDefault")
    @JsonAlias({"default"})
    private Boolean isDefault;

    @JsonProperty("isGlobal")
    @JsonAlias({"global"})
    private Boolean isGlobal;

    @JsonProperty("isGlobalDefault")
    @JsonAlias({"globalDefault"})
    private Boolean isGlobalDefault;

    private JsonNode state;
    private Integer schemaVersion;
    private Integer sortOrder;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public Boolean getGlobal() {
        return isGlobal;
    }

    public void setGlobal(Boolean global) {
        isGlobal = global;
    }

    public Boolean getGlobalDefault() {
        return isGlobalDefault;
    }

    public void setGlobalDefault(Boolean globalDefault) {
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
