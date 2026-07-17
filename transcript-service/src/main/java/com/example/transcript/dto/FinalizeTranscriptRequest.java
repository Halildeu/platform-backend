package com.example.transcript.dto;

import jakarta.validation.constraints.Min;

/** Explicit producer-owned occurrence version for canonical transcript finalization. */
public record FinalizeTranscriptRequest(@Min(1) long finalizationVersion) {
}
