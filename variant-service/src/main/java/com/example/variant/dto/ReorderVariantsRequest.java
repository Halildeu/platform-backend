package com.example.variant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public class ReorderVariantsRequest {

    @NotBlank
    private String gridId;

    @NotNull
    @Size(min = 1)
    @Valid
    private List<Item> items;

    public String getGridId() {
        return gridId;
    }

    public void setGridId(String gridId) {
        this.gridId = gridId;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    public static class Item {

        @NotNull
        private UUID id;

        @NotNull
        private Integer sortOrder;

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }
    }
}
