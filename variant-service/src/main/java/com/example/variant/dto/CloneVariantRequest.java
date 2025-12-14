package com.example.variant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CloneVariantRequest {

    private String name;

    @JsonProperty("setDefault")
    private Boolean setDefault;

    @JsonProperty("setSelected")
    private Boolean setSelected;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getSetDefault() {
        return setDefault;
    }

    public void setSetDefault(Boolean setDefault) {
        this.setDefault = setDefault;
    }

    public Boolean getSetSelected() {
        return setSelected;
    }

    public void setSetSelected(Boolean setSelected) {
        this.setSelected = setSelected;
    }
}

