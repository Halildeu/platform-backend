package com.example.report.registry;

public record ToneRule(
        String condition,
        String tone
) {
    public ToneRule {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("ToneRule condition must not be blank");
        }
        if (tone == null || tone.isBlank()) {
            tone = "default";
        }
    }
}
