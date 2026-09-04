package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechContextTermMergerTest {

    @Test
    void meetingTermsLeadAndExactDuplicatesCollapse() {
        List<String> merged = SpeechContextTermMerger.merge(
                List.of("Acme Holding", "OpenFGA"),
                List.of("openfga", "OpenFGA", "Zanzibar"));

        // Case-sensitive on purpose: "openfga" is a distinct spelling hint from "OpenFGA".
        assertThat(merged).containsExactly("Acme Holding", "OpenFGA", "openfga", "Zanzibar");
    }

    @Test
    void blankNullAndOversizedEntriesDrop_whitespaceCollapses() {
        List<String> client = new ArrayList<>(Arrays.asList(
                "  spaced \t  term ", "", "   ", null,
                "x".repeat(SpeechContextTermMerger.MAX_TERM_LENGTH + 1)));

        List<String> merged = SpeechContextTermMerger.merge(null, client);

        assertThat(merged).containsExactly("spaced term");
    }

    @Test
    void mergedListIsCappedWithMeetingPrecedence() {
        List<String> meeting = new ArrayList<>();
        List<String> client = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            meeting.add("m" + i);
            client.add("c" + i);
        }

        List<String> merged = SpeechContextTermMerger.merge(meeting, client);

        assertThat(merged).hasSize(SpeechContextTermMerger.MAX_MERGED_TERMS);
        assertThat(merged.subList(0, 40)).containsExactlyElementsOf(meeting);
        assertThat(merged.get(SpeechContextTermMerger.MAX_MERGED_TERMS - 1)).isEqualTo("c23");
    }

    @Test
    void emptyInputsYieldEmptyList() {
        assertThat(SpeechContextTermMerger.merge(null, null)).isEmpty();
        assertThat(SpeechContextTermMerger.merge(List.of(), List.of())).isEmpty();
    }
}
