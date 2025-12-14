package com.example.variant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class VariantPreferenceUpdateRequest {

    @JsonProperty("isDefault")
    private Boolean isDefault;

    @JsonProperty("isSelected")
    private Boolean isSelected;

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public Boolean getSelected() {
        return isSelected;
    }

    public void setSelected(Boolean selected) {
        isSelected = selected;
    }
}

