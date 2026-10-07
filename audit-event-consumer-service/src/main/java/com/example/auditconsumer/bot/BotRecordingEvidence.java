package com.example.auditconsumer.bot;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "bot_recording_evidence")
class BotRecordingEvidence {
    @Id UUID id;
    @Column(name = "intent_id", nullable = false) UUID intentId;
    @Column(nullable = false) long revision;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text") String snapshotJson;
    @Column(name = "snapshot_hash", nullable = false, length = 64) String snapshotHash;
    protected BotRecordingEvidence() {}
}
