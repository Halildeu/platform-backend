-- Faz 24 meeting operations: ordered agenda items owned by a meeting.

CREATE TABLE meeting_agenda_items (
    id                       UUID          NOT NULL,
    meeting_id               UUID          NOT NULL,
    tenant_id                UUID          NOT NULL,
    org_id                   UUID,
    position_index           INTEGER       NOT NULL,
    title                    VARCHAR(512)  NOT NULL,
    detail                   VARCHAR(4000),
    owner_subject            VARCHAR(255),
    planned_duration_minutes INTEGER,
    status                   VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    created_by_subject       VARCHAR(255)  NOT NULL,
    last_updated_by_subject  VARCHAR(255)  NOT NULL,
    created_at               TIMESTAMPTZ   NOT NULL,
    updated_at               TIMESTAMPTZ   NOT NULL,
    version                  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_meeting_agenda_items PRIMARY KEY (id),
    CONSTRAINT meeting_agenda_items_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT meeting_agenda_items_position_non_negative
        CHECK (position_index >= 0),
    CONSTRAINT meeting_agenda_items_duration_positive
        CHECK (planned_duration_minutes IS NULL OR planned_duration_minutes > 0),
    CONSTRAINT fk_meeting_agenda_items_meeting
        FOREIGN KEY (meeting_id, tenant_id)
        REFERENCES meetings(id, tenant_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_meeting_agenda_items_meeting_order
    ON meeting_agenda_items(meeting_id, position_index, created_at, id);
CREATE INDEX idx_meeting_agenda_items_org_id
    ON meeting_agenda_items(org_id);

DROP TRIGGER IF EXISTS meeting_agenda_items_org_id_compat ON meeting_agenda_items;
CREATE TRIGGER meeting_agenda_items_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_agenda_items
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();
