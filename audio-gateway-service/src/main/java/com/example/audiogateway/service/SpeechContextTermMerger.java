package com.example.audiogateway.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Merges the consent-bound meeting vocabulary (canonical meeting contract,
 * platform-backend#1024) with the terms a recorder sends on session start.
 *
 * <p>Policy: meeting terms lead in their configured order, client terms follow, exact
 * duplicates collapse (first occurrence wins, so a meeting term is never displaced by a
 * client spelling), whitespace is collapsed, blank / null / oversized entries are dropped,
 * and the merged list is capped at {@link #MAX_MERGED_TERMS} with meeting precedence.
 * Matching is case-sensitive on purpose: STT vocabulary biasing treats casing as part of
 * the spelling ("OpenFGA" and "openfga" are distinct hints).
 */
public final class SpeechContextTermMerger {

    /** 32 meeting-contract terms + 32 recorder terms; the STT provider limit is far higher. */
    public static final int MAX_MERGED_TERMS = 64;

    /** Mirrors the per-term limit enforced by meeting-service's SpeechContextTerms. */
    public static final int MAX_TERM_LENGTH = 64;

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private SpeechContextTermMerger() {
    }

    public static List<String> merge(final List<String> meetingTerms, final List<String> clientTerms) {
        final Set<String> merged = new LinkedHashSet<>();
        addAll(merged, meetingTerms);
        addAll(merged, clientTerms);
        return List.copyOf(merged);
    }

    private static void addAll(final Set<String> target, final List<String> terms) {
        if (terms == null) {
            return;
        }
        for (final String raw : terms) {
            if (target.size() >= MAX_MERGED_TERMS) {
                return;
            }
            if (raw == null) {
                continue;
            }
            final String term = WHITESPACE_RUN.matcher(raw.strip()).replaceAll(" ");
            if (term.isEmpty() || term.length() > MAX_TERM_LENGTH) {
                continue;
            }
            target.add(term);
        }
    }
}
