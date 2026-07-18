package com.example.transcript.dto;

/** Ordered, finalized transcript segment returned only to the analysis worker. */
public record CanonicalTranscriptSegmentDto(String text, double start, Double end) { }
