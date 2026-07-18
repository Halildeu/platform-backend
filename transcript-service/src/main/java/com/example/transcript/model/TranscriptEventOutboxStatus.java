package com.example.transcript.model;

public enum TranscriptEventOutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    DEAD
}
