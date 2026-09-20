package com.example.common.meeting.speakers;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** User-supplied display labels. Never part of machine transcript/event content. */
public final class SpeakerLabels {
    public static final int MAX_LABELS = 256;
    public static final int MAX_NAME_CODEPOINTS = 80;
    private static final Pattern SPEAKER = Pattern.compile("^(S[1-9][0-9]{0,2}|SPEAKER_[0-9]{2,3})$");
    private SpeakerLabels() { }

    public record Label(UUID scope, String speaker, String name) {
        public Label { requireKey(scope, speaker); name = normalizeName(name); }
        public String key() { return scope + ":" + speaker; }
    }
    /** One-key mutation. null removes a label; absent keys are untouched. */
    public record Edit(UUID scope, String speaker, String name, long expectedRevision) {
        public Edit {
            requireKey(scope, speaker);
            if (expectedRevision < 0 || expectedRevision >= 9007199254740991L) throw new IllegalArgumentException("LABEL_REVISION_INVALID");
            if (name != null) name = normalizeName(name);
        }
        public String key() { return scope + ":" + speaker; }
    }
    public record Snapshot(UUID tenantId, UUID meetingId, UUID sessionId, long finalizationVersion,
            UUID analysisRunId, String transcriptSha256, long revision, boolean editable, List<Label> labels) {
        public Snapshot {
            if (tenantId == null || meetingId == null || sessionId == null || analysisRunId == null
                    || finalizationVersion < 1 || revision < 0 || revision > 9007199254740991L
                    || transcriptSha256 == null || !transcriptSha256.matches("[a-f0-9]{64}")
                    || labels == null || labels.size() > MAX_LABELS) throw new IllegalArgumentException("LABEL_SNAPSHOT_INVALID");
            labels = List.copyOf(labels);
            if (labels.stream().map(Label::key).distinct().count() != labels.size())
                throw new IllegalArgumentException("LABEL_KEYS_DUPLICATED");
        }
    }
    public static void requireKey(UUID scope, String speaker) {
        if (scope == null || speaker == null || !SPEAKER.matcher(speaker).matches())
            throw new IllegalArgumentException("LABEL_SPEAKER_INVALID");
    }
    public static String normalizeName(String input) {
        if (input == null || input.length() > MAX_NAME_CODEPOINTS * 2) throw new IllegalArgumentException("LABEL_NAME_INVALID");
        String name = input.replaceAll("^[\\p{Zs}]+|[\\p{Zs}]+$", "");
        if (name.isBlank() || name.codePointCount(0, name.length()) > MAX_NAME_CODEPOINTS)
            throw new IllegalArgumentException("LABEL_NAME_INVALID");
        if (input.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.SURROGATE
                || cp == 0x2028 || cp == 0x2029)) throw new IllegalArgumentException("LABEL_NAME_INVALID");
        return name;
    }
}
