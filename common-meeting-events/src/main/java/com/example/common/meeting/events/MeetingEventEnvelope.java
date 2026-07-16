package com.example.common.meeting.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The versioned, transport-free meeting event — #802 slice 1.
 *
 * <p>This is the ONE in-memory shape every producer builds. It is deliberately not a
 * persistence entity and not a wire record: a producer maps it onto its OWN outbox row
 * (its table, its migration, its poller), and {@link MeetingEventV1Serializer} maps it
 * onto the {@code meeting.event.v1} bytes. Those two mappings are the only places that
 * know about storage and wire respectively.
 *
 * <h2>Envelope core</h2>
 * {@code eventType, eventKey, producer, meetingId, tenantId, orgId, occurredAt,
 * aggregateType, aggregateId, aggregateRevision} — the fields every meeting event has,
 * whatever produced it. {@code schema} is not a field: it is the version tag owned by
 * whichever serializer renders the envelope, so one envelope can be rendered as v1
 * today and v2 later without the model carrying a version it cannot honour.
 *
 * <h2>Fields the v1 wire does not carry yet</h2>
 * {@code producer}, {@code aggregateType} and {@code aggregateRevision} are modelled
 * here but are NOT rendered by {@link MeetingEventV1Serializer} — the v1 bytes are
 * frozen (#802 first-PR acceptance 2) and adding a field to them would break every
 * pinned consumer. They earn their keep immediately regardless: {@link MeetingEventKeys}
 * derives event keys from them, and they are what a future {@code meeting.event.v2}
 * would render. Any structural move is v2 + consumer dual-read + producer switch — never
 * a v1/v2 dual-publish of the same logical event.
 */
public final class MeetingEventEnvelope {

    private final MeetingEventType eventType;
    private final String eventKey;
    private final String producer;
    private final UUID meetingId;
    private final UUID tenantId;
    private final UUID orgId;
    private final Instant occurredAt;
    private final String aggregateType;
    private final UUID aggregateId;
    private final long aggregateRevision;
    private final MeetingEventPayload payload;

    private MeetingEventEnvelope(final Builder builder) {
        this.eventType = builder.eventType;
        this.eventKey = builder.eventKey;
        this.producer = builder.producer;
        this.meetingId = builder.meetingId;
        this.tenantId = builder.tenantId;
        this.orgId = builder.orgId;
        this.occurredAt = builder.occurredAt;
        this.aggregateType = builder.aggregateType;
        this.aggregateId = builder.aggregateId;
        this.aggregateRevision = builder.aggregateRevision;
        this.payload = builder.payload;
    }

    public static Builder builder() {
        return new Builder();
    }

    public MeetingEventType eventType() {
        return eventType;
    }

    /** Deterministic idempotency key — see {@link MeetingEventKeys}. */
    public String eventKey() {
        return eventKey;
    }

    /** Which service emitted this (e.g. {@code meeting-service}). Not in the v1 wire. */
    public String producer() {
        return producer;
    }

    public UUID meetingId() {
        return meetingId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    /** Nullable — a tenant need not be organisation-scoped. */
    public UUID orgId() {
        return orgId;
    }

    /** When the fact happened at the producer. Rendered as {@code generatedAt} in v1. */
    public Instant occurredAt() {
        return occurredAt;
    }

    /** The kind of aggregate this event is about (e.g. {@code meeting.analysis.run}). Not in the v1 wire. */
    public String aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    /**
     * Producer-owned occurrence counter for {@link #aggregateId}.
     *
     * <p>This is what makes a legitimately repeated fact a NEW event rather than a
     * duplicate suppressed by the unique key: a re-revoked consent or a re-finalized
     * transcript is a real new occurrence. For the v1 analysis events the revision is
     * always {@code 0} because {@code analysisRunId} is itself minted per occurrence —
     * a re-analysis is a new run, so the run id already discriminates.
     */
    public long aggregateRevision() {
        return aggregateRevision;
    }

    public MeetingEventPayload payload() {
        return payload;
    }

    public static final class Builder {

        private MeetingEventType eventType;
        private String eventKey;
        private String producer;
        private UUID meetingId;
        private UUID tenantId;
        private UUID orgId;
        private Instant occurredAt;
        private String aggregateType;
        private UUID aggregateId;
        private long aggregateRevision;
        private MeetingEventPayload payload;

        private Builder() {
        }

        public Builder eventType(final MeetingEventType value) {
            this.eventType = value;
            return this;
        }

        public Builder eventKey(final String value) {
            this.eventKey = value;
            return this;
        }

        public Builder producer(final String value) {
            this.producer = value;
            return this;
        }

        public Builder meetingId(final UUID value) {
            this.meetingId = value;
            return this;
        }

        public Builder tenantId(final UUID value) {
            this.tenantId = value;
            return this;
        }

        public Builder orgId(final UUID value) {
            this.orgId = value;
            return this;
        }

        public Builder occurredAt(final Instant value) {
            this.occurredAt = value;
            return this;
        }

        public Builder aggregateType(final String value) {
            this.aggregateType = value;
            return this;
        }

        public Builder aggregateId(final UUID value) {
            this.aggregateId = value;
            return this;
        }

        public Builder aggregateRevision(final long value) {
            this.aggregateRevision = value;
            return this;
        }

        public Builder payload(final MeetingEventPayload value) {
            this.payload = value;
            return this;
        }

        /**
         * Builds after {@link MeetingEventValidator#requireValid} — an invalid envelope
         * never exists, so no caller has to defend against a half-built one.
         *
         * <p>{@code eventKey} is derived from the payload when the caller did not set
         * one, which is the normal producer path: the key is a function of the fact, not
         * a caller's choice. A caller that DOES set one (a poller rehydrating an
         * envelope from a stored row) has it checked against the derivation, so key
         * drift surfaces here instead of as silent duplicate delivery downstream.
         */
        public MeetingEventEnvelope build() {
            if (this.eventKey == null && this.payload != null) {
                this.eventKey = MeetingEventKeys.forPayload(this.payload);
            }
            final MeetingEventEnvelope envelope = new MeetingEventEnvelope(this);
            MeetingEventValidator.requireValid(envelope);
            return envelope;
        }
    }
}
