package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.meeting.dto.v1.admin.MeetingIntelligenceCitation;
import com.example.meeting.dto.v1.internal.MeetingAnalysisActionIngest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden-vector tests for {@link MeetingAnalysisPayloadHasher} — the idempotency
 * contract's determinism + sensitivity (Faz 24, platform-ai#244 BE-1c).
 *
 * <p>The hash is computed over the normalised value object, so each test pins a
 * property the idempotency comparison relies on: equal content hashes equally,
 * any content change flips the hash, list ORDER matters (it becomes the child
 * ordinal), and the null-normalisation of the DTO makes {@code null ≡ []} and
 * {@code null int ≡ 0}.
 */
class MeetingAnalysisPayloadHasherTest {

    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String SHA = "a".repeat(64);
    private static final Instant GEN = Instant.parse("2026-07-11T10:00:00Z");

    private final MeetingAnalysisPayloadHasher hasher = new MeetingAnalysisPayloadHasher();

    @Test
    void hashIsLowercaseSha256Hex() {
        String hash = hasher.hash(MEETING, TENANT, RUN, base().build());
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    void identicalContent_hashesIdentically() {
        String a = hasher.hash(MEETING, TENANT, RUN, base().build());
        String b = hasher.hash(MEETING, TENANT, RUN, base().build());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void changingAnyContentField_changesTheHash() {
        String baseHash = hasher.hash(MEETING, TENANT, RUN, base().build());
        assertThat(hasher.hash(MEETING, TENANT, RUN, base().summary("different").build()))
                .isNotEqualTo(baseHash);
        assertThat(hasher.hash(MEETING, TENANT, RUN, base().transcriptSha("b".repeat(64)).build()))
                .isNotEqualTo(baseHash);
        assertThat(hasher.hash(MEETING, TENANT, RUN, base().generatedAt(GEN.plusSeconds(1)).build()))
                .isNotEqualTo(baseHash);
    }

    @Test
    void differentScope_changesTheHash() {
        String baseHash = hasher.hash(MEETING, TENANT, RUN, base().build());
        // The hash binds runId + meetingId + tenantId, so the same payload for a
        // different target can never collide to an equal hash.
        assertThat(hasher.hash(UUID.randomUUID(), TENANT, RUN, base().build())).isNotEqualTo(baseHash);
        assertThat(hasher.hash(MEETING, UUID.randomUUID(), RUN, base().build())).isNotEqualTo(baseHash);
        assertThat(hasher.hash(MEETING, TENANT, UUID.randomUUID(), base().build())).isNotEqualTo(baseHash);
    }

    @Test
    void nullList_equalsEmptyList() {
        // The DTO compact constructor normalises null → empty, so both hash the same.
        String withNull = hasher.hash(MEETING, TENANT, RUN, base().decisions(null).actions(null).build());
        String withEmpty = hasher.hash(MEETING, TENANT, RUN, base().decisions(List.of()).actions(List.of()).build());
        assertThat(withNull).isEqualTo(withEmpty);
    }

    @Test
    void nullCounts_equalZero() {
        String nulls = hasher.hash(MEETING, TENANT, RUN, base().ungrounded(null).redactionCount(null).redacted(null).build());
        String zeros = hasher.hash(MEETING, TENANT, RUN, base().ungrounded(0).redactionCount(0).redacted(false).build());
        assertThat(nulls).isEqualTo(zeros);
    }

    @Test
    void decisionOrder_changesTheHash() {
        String ab = hasher.hash(MEETING, TENANT, RUN, base().decisions(List.of("a", "b")).build());
        String ba = hasher.hash(MEETING, TENANT, RUN, base().decisions(List.of("b", "a")).build());
        assertThat(ab).isNotEqualTo(ba);
    }

    @Test
    void actionOrder_changesTheHash() {
        String ab = hasher.hash(MEETING, TENANT, RUN, base()
                .actions(List.of(action("a"), action("b"))).build());
        String ba = hasher.hash(MEETING, TENANT, RUN, base()
                .actions(List.of(action("b"), action("a"))).build());
        assertThat(ab).isNotEqualTo(ba);
    }

    @Test
    void astralUnicode_isDeterministic() {
        String emoji = "karar 🚀 uzay"; // rocket = astral surrogate pair
        String a = hasher.hash(MEETING, TENANT, RUN, base().decisions(List.of(emoji)).build());
        String b = hasher.hash(MEETING, TENANT, RUN, base().decisions(List.of(emoji)).build());
        assertThat(a).isEqualTo(b).matches("^[0-9a-f]{64}$");
    }

    @Test
    void citationContent_isReflectedInTheHash() {
        MeetingIntelligenceCitation c1 = new MeetingIntelligenceCitation(
                "claim", 0, "src", 0.9, true, "verified", null, 1.0, 0, 10, "srch", "qh");
        MeetingIntelligenceCitation c2 = new MeetingIntelligenceCitation(
                "claim-2", 0, "src", 0.9, true, "verified", null, 1.0, 0, 10, "srch", "qh");
        String h1 = hasher.hash(MEETING, TENANT, RUN, base().citations(List.of(c1)).build());
        String h2 = hasher.hash(MEETING, TENANT, RUN, base().citations(List.of(c2)).build());
        assertThat(h1).isNotEqualTo(h2);
        // stable for equal content
        assertThat(hasher.hash(MEETING, TENANT, RUN, base().citations(List.of(c1)).build())).isEqualTo(h1);
    }

    // ─────────────────────────── builder ───────────────────────────

    private static MeetingAnalysisActionIngest action(String text) {
        return new MeetingAnalysisActionIngest(text, "assignee", null);
    }

    private static Builder base() {
        return new Builder();
    }

    /** Minimal mutable builder so each test varies one field over a stable base. */
    private static final class Builder {
        private String transcriptSha = SHA;
        private String summary = "ozet";
        private Instant generatedAt = GEN;
        private List<String> decisions = List.of("karar-1");
        private List<MeetingAnalysisActionIngest> actions = List.of(action("aksiyon-1"));
        private List<MeetingIntelligenceCitation> citations = List.of();
        private Integer ungrounded = 0;
        private Integer redactionCount = 0;
        private Boolean redacted = Boolean.FALSE;

        Builder transcriptSha(String v) { this.transcriptSha = v; return this; }
        Builder summary(String v) { this.summary = v; return this; }
        Builder generatedAt(Instant v) { this.generatedAt = v; return this; }
        Builder decisions(List<String> v) { this.decisions = v; return this; }
        Builder actions(List<MeetingAnalysisActionIngest> v) { this.actions = v; return this; }
        Builder citations(List<MeetingIntelligenceCitation> v) { this.citations = v; return this; }
        Builder ungrounded(Integer v) { this.ungrounded = v; return this; }
        Builder redactionCount(Integer v) { this.redactionCount = v; return this; }
        Builder redacted(Boolean v) { this.redacted = v; return this; }

        MeetingAnalysisResultIngestRequest build() {
            return new MeetingAnalysisResultIngestRequest(
                    null, "SES-1", transcriptSha, "5-adr0043", "gpt-x", "openai", "p1",
                    summary, "verified", List.of(), citations, List.of(),
                    ungrounded, redacted, redactionCount, generatedAt, decisions, actions, null);
        }
    }
}
