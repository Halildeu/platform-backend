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
        int audioDurationMs) {
}
