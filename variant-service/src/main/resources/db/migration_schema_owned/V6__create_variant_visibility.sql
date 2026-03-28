CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

CREATE TABLE IF NOT EXISTS variant_visibility (
    id BIGSERIAL PRIMARY KEY,
    variant_id UUID NOT NULL,
    visibility_type VARCHAR(32) NOT NULL,
    ref_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    created_by VARCHAR(64),

    CONSTRAINT fk_variant_visibility_variant
        FOREIGN KEY (variant_id) REFERENCES user_grid_variants(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_visibility_type
        CHECK (visibility_type IN ('GLOBAL', 'COMPANY', 'USER', 'ROLE'))
);

CREATE INDEX IF NOT EXISTS idx_variant_vis_type_ref
    ON variant_visibility (visibility_type, ref_id);

CREATE INDEX IF NOT EXISTS idx_variant_vis_variant
    ON variant_visibility (variant_id);
