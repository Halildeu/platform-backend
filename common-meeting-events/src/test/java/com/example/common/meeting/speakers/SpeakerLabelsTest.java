package com.example.common.meeting.speakers;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpeakerLabelsTest {
    private final UUID scope = UUID.randomUUID();
    @Test void unicodeNamesRemainDisplayValuesAndNullMeansRemoval() {
        assertThat(new SpeakerLabels.Label(scope, "S1", "  Zeynep 📝  ").name()).isEqualTo("Zeynep 📝");
        assertThat(new SpeakerLabels.Edit(scope, "S1", null, 7).name()).isNull();
        assertThat(new SpeakerLabels.Label(scope, "S2", "📝".repeat(80)).name()).hasSize(160);
    }
    @Test void rejectsUnknownSpeakersControlBidiBrokenSurrogatesAndOversizedNames() {
        for (String speaker : new String[] {"UU", "S0", "Halil", "S1000"})
            assertThatThrownBy(() -> new SpeakerLabels.Label(scope, speaker, "A")).isInstanceOf(IllegalArgumentException.class);
        for (String name : new String[] {"", " ", "A\nB", "A\u202eB", "A\u0000B", "\ud800", "📝".repeat(81)})
            assertThatThrownBy(() -> new SpeakerLabels.Label(scope, "S1", name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeakerLabels.Edit(scope, "S1", "A", -1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void snapshotRejectsDuplicatedIdentitiesButNotDuplicatedNames() {
        var label = new SpeakerLabels.Label(scope, "S1", "A");
        assertThatThrownBy(() -> snapshot(List.of(label, label))).isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot(List.of(label, new SpeakerLabels.Label(scope, "S2", "A"))).labels()).hasSize(2);
        assertThatThrownBy(() -> snapshot(java.util.Collections.nCopies(257, label))).isInstanceOf(IllegalArgumentException.class);
    }
    private SpeakerLabels.Snapshot snapshot(List<SpeakerLabels.Label> labels) {
        return new SpeakerLabels.Snapshot(scope, scope, scope, 1, scope, "a".repeat(64), 0, true, labels);
    }
}
