package com.example.audiogateway.service;

import com.example.audiogateway.dto.AudioFormat;

/** Copied, in-flight-only audio and routing metadata passed to an STT provider adapter. */
public record DirectSttTranscriptionRequest(
        byte[] audio,
        AudioFormat audioFormat,
        int sampleRateHz,
        int channels,
        String meetingId,
        String sessionId,
        String deviceId,
        String language,
        int audioDurationMs,
        /**
         * Gateway window sequence (#3746 session attribution). Forwards complete out of
         * order; live-stt's transient session store re-joins windows by this seq. Null on
         * providers/paths that do not participate in attribution.
         */
        Integer windowSeq,
        /**
         * Transport epoch the {@link #windowSeq} belongs to (#3746). Window numbering
         * restarts at 0 on every new sequence space, so live-stt keys its transient
         * session store by session+epoch. Null when windowSeq is null.
         */
        Long transportEpoch) {

    public DirectSttTranscriptionRequest(
            final byte[] audio,
            final AudioFormat audioFormat,
            final int sampleRateHz,
            final int channels,
            final String meetingId,
            final String sessionId,
            final String deviceId,
            final String language,
            final int audioDurationMs) {
        this(audio, audioFormat, sampleRateHz, channels, meetingId, sessionId, deviceId,
                language, audioDurationMs, null, null);
    }
}
