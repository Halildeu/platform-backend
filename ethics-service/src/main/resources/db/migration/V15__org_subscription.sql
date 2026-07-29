-- ES-403. Which products an organisation holds. The definitions live in code (they move with
-- a deployment, which is their audit trail); what a given customer bought is data.
--
-- No amounts, no dates beyond validity, no billing identifiers: this table answers one
-- question — "does this organisation hold this product right now" — and a table that also
-- knew what was paid would invite the next feature to read entitlement out of an invoice.
CREATE TABLE ethics_org_subscription (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    product_id VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_ethics_subscription_window CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);

CREATE INDEX ix_ethics_subscription_org ON ethics_org_subscription (org_id, active);
