package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;
import reactor.core.publisher.Mono;

/** Provider-neutral outbound STT port used by the bounded Direct-STT dispatcher. */
public interface DirectSttTranscriptionClient {

    Mono<TranscriptResult> transcribe(DirectSttTranscriptionRequest request);

    /** Low-cardinality provider identifier for operational metrics. */
    String providerId();

    /** Stable audit identifier; defaults to the provider id for new adapters. */
    default String computePlaneId() {
        return providerId();
    }
}
