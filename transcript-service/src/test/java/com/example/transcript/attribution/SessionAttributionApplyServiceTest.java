package com.example.transcript.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.common.meeting.events.SpeakerAttribution;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.service.SessionErasureFence;
import com.example.transcript.service.SessionErasureFence.SessionErasedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #3746 BE-D4b — attribution apply direction tests: the consumer-derived turn must
 * cover the stored window text, UU stays scalar-null, first write wins, redacted and
 * erased paths never mutate anything.
 */
class SessionAttributionApplyServiceTest {

    private static final String MEETING = "9b2c5a89-f39a-47da-a33d-a2859b468e8b";

    private TranscriptSegmentRepository segments;
    private SessionErasureFence fence;
    private SessionAttributionApplyService service;

    @BeforeEach
    void setUp() {
        segments = mock(TranscriptSegmentRepository.class);
        fence = mock(SessionErasureFence.class);
        service = new SessionAttributionApplyService(segments, fence);
    }

    private static SessionAttributionEvent event(final String speaker) {
        return SessionAttributionEvent.parse("""
                {"schema":"directSttSessionAttribution.v1","tenantId":"42",
                 "meetingId":"%s","sourceSessionId":"SES-1","transportEpoch":3,
                 "model":"pyannote/speaker-diarization-3.1",
                 "modelRevision":"84fd25912480287da0247647c3d2b4853cb3ee5d",
                 "audioSampleCount":160000,"generatedAt":"2026-09-29T13:00:00Z",
                 "windows":[{"windowSeq":0,"speaker":"%s","startMs":0,"endMs":5000,
                             "dominanceRatio":0.9}]}
                """.formatted(MEETING, speaker));
    }

    private TranscriptSegment segment(final String text, final TranscriptSegmentStatus status) {
        final TranscriptSegment segment = new TranscriptSegment();
        segment.setTextDraft(text);
        segment.setStartTime(10.0);
        segment.setEndTime(15.0);
        segment.setStatus(status);
        return segment;
    }

    private void stubFind(final TranscriptSegment segment) {
        when(segments.findDirectSttSourceTransportWindow(
                any(UUID.class), any(UUID.class), anyString(), anyLong(), anyLong()))
                .thenReturn(Optional.ofNullable(segment));
    }

    @Test
    void appliesWholeWindowTurnAndScalarSpeakerId() {
        final TranscriptSegment segment = segment("Merhaba dünya.", TranscriptSegmentStatus.DRAFT);
        stubFind(segment);

        final var outcome = service.apply(event("SPEAKER_00"));

        assertThat(outcome.applied()).isEqualTo(1);
        final SpeakerAttribution attribution = segment.getSpeakerAttribution();
        assertThat(attribution).isNotNull();
        assertThat(attribution.turns()).hasSize(1);
        final SpeakerAttribution.Turn turn = attribution.turns().get(0);
        assertThat(turn.speaker()).isEqualTo("SPEAKER_00");
        assertThat(turn.textStart()).isZero();
        assertThat(turn.textEnd()).isEqualTo("Merhaba dünya.".length());
        assertThat(turn.startMs()).isZero();
        assertThat(turn.endMs()).isEqualTo(5_000L);
        assertThat(segment.getSpeakerId())
                .isEqualTo(attribution.speakerId("SPEAKER_00"))
                .isNotNull();
        verify(segments).save(segment);
        // The attribution validates against the ingest parser's own contract.
        SpeakerAttribution.parse(attribution.encode(), attribution.scope(),
                "Merhaba dünya.", 5_000L);
    }

    @Test
    void unknownSpeakerKeepsScalarNull() {
        final TranscriptSegment segment = segment("Tartışmalı pencere.", TranscriptSegmentStatus.DRAFT);
        stubFind(segment);

        final var outcome = service.apply(event("UU"));

        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(segment.getSpeakerAttribution()).isNotNull();
        assertThat(segment.getSpeakerId()).isNull();
    }

    @Test
    void firstWriteWins() {
        final TranscriptSegment segment = segment("Zaten atanmış.", TranscriptSegmentStatus.DRAFT);
        segment.setSpeakerAttribution(new SpeakerAttribution(
                UUID.randomUUID(),
                List.of(new SpeakerAttribution.Turn("S1", 0, 14, 0L, 1_000L))));
        stubFind(segment);

        final var outcome = service.apply(event("SPEAKER_00"));

        assertThat(outcome.alreadyApplied()).isEqualTo(1);
        assertThat(outcome.applied()).isZero();
        assertThat(segment.getSpeakerAttribution().turns().get(0).speaker()).isEqualTo("S1");
        verify(segments, never()).save(any());
    }

    @Test
    void redactedWindowIsNeverTouched() {
        final TranscriptSegment segment = segment("[redacted]", TranscriptSegmentStatus.REDACTED);
        stubFind(segment);

        final var outcome = service.apply(event("SPEAKER_00"));

        assertThat(outcome.skippedRedacted()).isEqualTo(1);
        assertThat(segment.getSpeakerAttribution()).isNull();
        verify(segments, never()).save(any());
    }

    @Test
    void missingWindowIsCountedNotFatal() {
        stubFind(null);
        final var outcome = service.apply(event("SPEAKER_00"));
        assertThat(outcome.missingWindows()).isEqualTo(1);
        verify(segments, never()).save(any());
    }

    @Test
    void blankTextIsInvalidWindow() {
        final TranscriptSegment segment = segment("   ", TranscriptSegmentStatus.DRAFT);
        stubFind(segment);
        final var outcome = service.apply(event("SPEAKER_00"));
        assertThat(outcome.invalidWindows()).isEqualTo(1);
        verify(segments, never()).save(any());
    }

    @Test
    void erasedSessionPropagatesAndMutatesNothing() {
        doThrow(new SessionErasedException())
                .when(fence).rejectSourceErased(any(UUID.class), any(UUID.class), anyString());
        assertThatThrownBy(() -> service.apply(event("SPEAKER_00")))
                .isInstanceOf(SessionErasedException.class);
        verify(segments, never()).save(any());
        verify(segments, never()).findDirectSttSourceTransportWindow(
                any(UUID.class), any(UUID.class), anyString(), anyLong(), anyLong());
    }
}
