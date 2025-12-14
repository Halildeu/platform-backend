package com.example.variant.theme.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "theme_registry")
public class ThemeRegistryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "\"key\"", nullable = false, unique = true)
    private String key;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String groupName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ThemeRegistryControlType controlType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ThemeRegistryEditableBy editableBy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "theme_registry_css_vars", joinColumns = @JoinColumn(name = "registry_id"))
    @Column(name = "css_var")
    private List<String> cssVars = new ArrayList<>();

    @Column
    private String description;

    @Column(name = "default_source")
    private String defaultSource;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public ThemeRegistryControlType getControlType() {
        return controlType;
    }

    public void setControlType(ThemeRegistryControlType controlType) {
        this.controlType = controlType;
    }

    public ThemeRegistryEditableBy getEditableBy() {
        return editableBy;
    }

    public void setEditableBy(ThemeRegistryEditableBy editableBy) {
        this.editableBy = editableBy;
    }

    public List<String> getCssVars() {
        return cssVars;
    }

    public void setCssVars(List<String> cssVars) {
        this.cssVars = cssVars;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultSource() {
        return defaultSource;
    }

    public void setDefaultSource(String defaultSource) {
        this.defaultSource = defaultSource;
    }
}
