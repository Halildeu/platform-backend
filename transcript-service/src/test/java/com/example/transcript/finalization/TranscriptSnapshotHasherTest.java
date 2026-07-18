package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptSnapshotHasherTest {

    private final TranscriptSnapshotHasher hasher = new TranscriptSnapshotHasher();

    @Test
    void machineSnapshotUsesDraftWithoutMutatingIt() {
        TranscriptSegment segment = segment();
        segment.setStatus(TranscriptSegmentStatus.DRAFT);
        segment.setTextDraft("makine taslagi");

        var snapshot = hasher.machineSnapshot(List.of(segment));

        assertThat(snapshot.segmentCount()).isOne();
        assertThat(snapshot.sha256()).matches("[0-9a-f]{64}");
        assertThat(segment.getStatus()).isEqualTo(TranscriptSegmentStatus.DRAFT);
        assertThat(segment.getTextDraft()).isEqualTo("makine taslagi");
        assertThat(segment.getTextFinal()).isNull();
    }

    @Test
    void sourceWindowIdentityChangesSnapshot() {
        TranscriptSegment first = segment();
        first.setStatus(TranscriptSegmentStatus.FINALIZED);
        first.setTextFinal("ayni metin");
        TranscriptSegment changed = copy(first);
        changed.setSourceLastChunkSeq(9L);

        assertThat(hasher.editorialSnapshot(List.of(first)).sha256())
                .isNotEqualTo(hasher.editorialSnapshot(List.of(changed)).sha256());
    }

    @Test
    void retainedTextOnRedactedSegmentFailsClosed() {
        TranscriptSegment segment = segment();
        segment.setStatus(TranscriptSegmentStatus.REDACTED);
        segment.setTextDraft("silinmemis kisisel veri");

        assertThatThrownBy(() -> hasher.machineSnapshot(List.of(segment)))
                .isInstanceOf(TranscriptSnapshotHasher.InvalidSnapshotException.class)
                .hasMessageContaining("REDACTED_TEXT_RETAINED");
    }

    private TranscriptSegment segment() {
        TranscriptSegment row = new TranscriptSegment();
        row.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        row.setSpeakerId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        row.setStartTime(1.25d);
        row.setEndTime(2.5d);
        row.setSourceSystem("DIRECT_STT");
        row.setSourceSessionId("SES-test");
        row.setSourceWindowSeq(3L);
        row.setSourceFirstChunkSeq(4L);
        row.setSourceLastChunkSeq(8L);
        row.setSourceChunkSeq(8L);
        return row;
    }

    private TranscriptSegment copy(TranscriptSegment source) {
        TranscriptSegment row = segment();
        row.setStatus(source.getStatus());
        row.setTextDraft(source.getTextDraft());
        row.setTextFinal(source.getTextFinal());
        return row;
    }
}
