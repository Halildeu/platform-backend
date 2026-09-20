package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.meeting.events.SpeakerAttribution;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalizedTranscriptSnapshotCodecTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String TEXT = "İş 📝";
    private final FinalizedTranscriptSnapshotCodec codec = new FinalizedTranscriptSnapshotCodec(
            new TranscriptSnapshotHasher(), new ObjectMapper(), true);

    @Test
    void rolloutDefaultWritesLegacyShapeButCanReadPreviouslyEnabledProjections() {
        var conservative = new FinalizedTranscriptSnapshotCodec(new TranscriptSnapshotHasher(), new ObjectMapper());
        assertThat(conservative.captureMachine(List.of(segment())).canonicalSegments()).doesNotContain("speakerAttribution");
        var enabledProjection = codec.captureMachine(List.of(segment()));
        assertThat(conservative.restore(occurrence(enabledProjection)).segments().getFirst().speakerAttribution()).isNotNull();
    }

    @Test
    void restoresGoldenLegacyProjectionWithNullFieldsAndExactHistoricHashes() {
        var occurrence = new TranscriptFinalization();
        occurrence.setSegmentCount(2);
        occurrence.setSnapshotSha256("a".repeat(64));
        occurrence.setCanonicalSegments("[{\"text\":\"İş 📝\",\"start\":0.0,\"end\":null},{\"text\":null,\"start\":1.0,\"end\":2.0}]");
        occurrence.setCanonicalProjectionSha256("38f77afd864c0c26b8f7bd5b1f83747c088bdd569077563e9354f3183ba075aa");
        occurrence.setCanonicalTranscript(TEXT);
        occurrence.setCanonicalTranscriptSha256("efb04ec66b6c69bc2d4d722e65145a729139f865238c71da5442fd5abae3079d");

        var restored = codec.restore(occurrence);
        assertThat(restored.canonicalSegments()).isEqualTo(occurrence.getCanonicalSegments());
        assertThat(restored.segments()).allSatisfy(segment -> assertThat(segment.speakerAttribution()).isNull());
    }

    @Test
    void freezesSpeakerTurnsWithTextAcrossLaterMutableSegmentChanges() {
        var segment = segment();
        var expected = segment.getSpeakerAttribution();
        var stored = codec.captureMachine(List.of(segment));
        segment.setSpeakerAttribution(attribution("S2", TEXT.length(), 1000));
        segment.setTextDraft("A later sentence");
        var restored = codec.restore(occurrence(stored));
        assertThat(restored.transcript()).isEqualTo(TEXT);
        assertThat(restored.segments().getFirst().speakerAttribution()).isEqualTo(expected);
    }

    @Test
    void metadataIsBoundByProjectionHashWithoutChangingTranscriptHash() {
        var segment = segment();
        var first = codec.captureMachine(List.of(segment));
        segment.setSpeakerAttribution(attribution("S2", TEXT.length(), 1000));
        var second = codec.captureMachine(List.of(segment));
        assertThat(first.transcriptSha256()).isEqualTo(second.transcriptSha256());
        assertThat(first.canonicalProjectionSha256()).isNotEqualTo(second.canonicalProjectionSha256());
        var occurrence = occurrence(first);
        occurrence.setCanonicalSegments(occurrence.getCanonicalSegments().replace("S1", "S2"));
        assertThatThrownBy(() -> codec.restore(occurrence)).hasMessage("CANONICAL_PROJECTION_HASH_MISMATCH");
    }

    @Test
    void malformedHashBoundTurnsFailEvenIfProjectionHashWasRecomputed() throws Exception {
        var occurrence = occurrence(codec.captureMachine(List.of(segment())));
        String malformed = occurrence.getCanonicalSegments().replace("\"textEnd\":5", "\"textEnd\":4");
        occurrence.setCanonicalSegments(malformed);
        occurrence.setCanonicalProjectionSha256(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(malformed.getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> codec.restore(occurrence)).hasMessage("CANONICAL_ATTRIBUTION_INVALID");
    }

    @Test
    void editorialChangesAndRedactionRemoveStaleOffsets() {
        var segment = segment();
        segment.setStatus(TranscriptSegmentStatus.FINALIZED);
        segment.setTextFinal(TEXT);
        assertThat(codec.captureEditorial(List.of(segment)).segments().getFirst().speakerAttribution()).isNotNull();
        segment.setTextFinal("Metin düzeltildi.");
        assertThat(codec.captureEditorial(List.of(segment)).segments().getFirst().speakerAttribution()).isNull();
        segment.setStatus(TranscriptSegmentStatus.REDACTED);
        segment.setTextFinal(null);
        segment.setTextDraft(null);
        var stored = codec.captureEditorial(List.of(segment));
        assertThat(stored.segments().getFirst().speakerAttribution()).isNull();
        assertThat(stored.transcript()).isEmpty();
        assertThat(stored.canonicalSegments()).doesNotContain("speakerAttribution");
    }

    @Test
    void legacyReconstructionNeverBorrowsCurrentSpeakerTurns() {
        var segment = segment();
        assertThat(codec.captureLegacyMachine(List.of(segment)).canonicalSegments()).doesNotContain("speakerAttribution");
        segment.setStatus(TranscriptSegmentStatus.FINALIZED);
        segment.setTextFinal(TEXT);
        assertThat(codec.captureLegacyEditorial(List.of(segment)).canonicalSegments()).doesNotContain("speakerAttribution");
    }

    @Test
    void rejectsForeignScopeOverlongTurnsAndIncompleteCoverage() {
        var segment = segment();
        segment.setSourceTransportEpoch(2L);
        assertThatThrownBy(() -> codec.captureMachine(List.of(segment))).hasMessage("CANONICAL_ATTRIBUTION_INVALID");
        segment.setSourceTransportEpoch(1L);
        segment.setSpeakerAttribution(attribution("S1", TEXT.length(), 1001));
        assertThatThrownBy(() -> codec.captureMachine(List.of(segment))).hasMessage("CANONICAL_ATTRIBUTION_INVALID");
        segment.setSpeakerAttribution(attribution("S1", 2, 1000));
        assertThatThrownBy(() -> codec.captureMachine(List.of(segment))).hasMessage("CANONICAL_ATTRIBUTION_INVALID");
    }

    @Test
    void handlesFloatingPointWindowBoundaryButRejectsUnknownOrZeroWindow() {
        var segment = segment();
        assertThat(codec.captureMachine(List.of(segment)).segments().getFirst().speakerAttribution()).isNotNull();
        segment.setEndTime(segment.getStartTime());
        assertThatThrownBy(() -> codec.captureMachine(List.of(segment))).hasMessage("CANONICAL_ATTRIBUTION_INVALID");
        segment.setEndTime(null);
        assertThatThrownBy(() -> codec.captureMachine(List.of(segment))).hasMessage("CANONICAL_ATTRIBUTION_INVALID");
    }

    private TranscriptSegment segment() {
        var segment = new TranscriptSegment();
        segment.setId(UUID.randomUUID());
        segment.setTenantId(TENANT);
        segment.setMeetingId(MEETING);
        segment.setTextDraft(TEXT);
        segment.setStartTime(1.001);
        segment.setEndTime(2.001);
        segment.setStatus(TranscriptSegmentStatus.DRAFT);
        segment.setSourceSessionId("SES-fixture");
        segment.setSourceTransportEpoch(1L);
        segment.setSpeakerAttribution(attribution("S1", TEXT.length(), 1000));
        return segment;
    }

    private SpeakerAttribution attribution(String speaker, int end, long endMs) {
        return new SpeakerAttribution(SpeakerAttribution.scope(TENANT.toString(), MEETING.toString(), "SES-fixture", 1),
                List.of(new SpeakerAttribution.Turn(speaker, 0, end, 0, endMs)));
    }

    private TranscriptFinalization occurrence(FinalizedTranscriptSnapshotCodec.StoredSnapshot stored) {
        var occurrence = new TranscriptFinalization();
        occurrence.setSegmentCount(stored.segmentCount());
        occurrence.setSnapshotSha256(stored.sourceSnapshotSha256());
        occurrence.setCanonicalTranscript(stored.transcript());
        occurrence.setCanonicalTranscriptSha256(stored.transcriptSha256());
        occurrence.setCanonicalSegments(stored.canonicalSegments());
        occurrence.setCanonicalProjectionSha256(stored.canonicalProjectionSha256());
        return occurrence;
    }
}
