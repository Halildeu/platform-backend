package com.example.common.meeting.events.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * The golden wire-compatibility fixtures — #802 slice 1, first-PR acceptance 2.
 *
 * <p>These are the {@code meeting.event.v1} bytes CAPTURED FROM THE PRE-REFACTOR
 * producer, before the common module existed. That provenance is the entire value of
 * the file: a golden generated from the new code would only prove the new code agrees
 * with itself. These prove the refactor did not move the wire.
 *
 * <p>They ship in {@code src/main/resources} rather than test resources on purpose —
 * every producer service must be able to assert its own output against the same bytes
 * from its own test source tree, without a test-jar dependency dance.
 *
 * <h2>Changing a golden</h2>
 * Don't. A failing golden is the alarm working. If the wire genuinely must change, that
 * is {@code meeting.event.v2} with a new fixture set beside these; v1's stay frozen
 * until v1 is retired.
 */
public final class MeetingEventGoldens {

    // The fixture inputs. A producer test builds its OWN entities from these values so
    // its output is comparable to the goldens byte for byte.

    /** Fixture analysis run id / aggregate id. */
    public static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    /** Fixture meeting id. */
    public static final UUID MEETING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    /** Fixture tenant id. */
    public static final UUID TENANT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    /** Fixture org id — the populated case; the null-holes fixtures leave it unset. */
    public static final UUID ORG_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    /** Fixture canonical meeting session id for transcript-ready. */
    public static final UUID TRANSCRIPT_SESSION_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    /** Fixture {@code occurredAt} / v1 {@code generatedAt}. */
    public static final Instant GENERATED_AT = Instant.parse("2026-07-11T10:00:00Z");
    /** Fixture action due date. */
    public static final Instant DUE_AT = Instant.parse("2026-07-20T09:00:00Z");
    /** Fixture assignee for the populated action. */
    public static final String ASSIGNEE = "ali@example.com";
    /** Fixture assignee for the null-holes action. */
    public static final String ASSIGNEE_NULL_HOLES = "veli@example.com";
    /** Fixture grounding verdict for the populated summary. */
    public static final String GROUNDING_STATUS = "verified";
    /** Ordinal of the null-holes action. */
    public static final int ORDINAL_NULL_HOLES = 9;

    private static final String BASE = "/golden/meeting.event.v1/";

    private MeetingEventGoldens() {
    }

    /** Fully populated {@code meeting.summary.ready}. */
    public static String summaryReady() {
        return read("summary-ready.json");
    }

    /** Fully populated {@code meeting.action.assigned}. */
    public static String actionAssigned() {
        return read("action-assigned.json");
    }

    /**
     * {@code meeting.summary.ready} with every nullable field null.
     *
     * <p>Pins the one break a "tidier" serializer would cause silently: v1 renders
     * absent values as EXPLICIT nulls ({@code "orgId":null}), not by omitting the key.
     * A consumer distinguishing "not set" from "not sent" reads that difference.
     */
    public static String summaryReadyNullHoles() {
        return read("summary-ready-null-holes.json");
    }

    /** {@code meeting.action.assigned} with every nullable field null. */
    public static String actionAssignedNullHoles() {
        return read("action-assigned-null-holes.json");
    }

    /** Fully populated {@code meeting.transcript.ready}. */
    public static String transcriptReady() {
        return read("transcript-ready.json");
    }

    /** The frozen v1 event key for the fixture summary event. */
    public static String summaryReadyKey() {
        return RUN_ID + "|meeting.summary.ready";
    }

    /** The frozen v1 event key for the fixture action event (ordinal 0). */
    public static String actionAssignedKey() {
        return RUN_ID + "|meeting.action.assigned|0";
    }

    private static String read(final String name) {
        try (InputStream in = MeetingEventGoldens.class.getResourceAsStream(BASE + name)) {
            if (in == null) {
                throw new IllegalStateException("Golden fixture missing from the jar: " + BASE + name);
            }
            // Trimmed so an editor's trailing newline never masquerades as a wire change.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read golden fixture " + name, e);
        }
    }
}
