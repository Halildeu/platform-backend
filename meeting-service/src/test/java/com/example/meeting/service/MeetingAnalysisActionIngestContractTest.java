package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.meeting.dto.v1.internal.MeetingAnalysisActionIngest;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MeetingAnalysisActionIngestContractTest {
    @Test
    void absentNullAndBlankDueTextPreserveLegacyIsoAndNullContracts() throws Exception {
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var legacy = mapper.readValue("""
                {"text":"action","assignee":null,"due":"2026-07-20T09:00:00Z"}
                """, MeetingAnalysisActionIngest.class);
        var explicitNull = mapper.readValue("""
                {"text":"action","assignee":null,"due":"2026-07-20T09:00:00Z","due_text":null}
                """, MeetingAnalysisActionIngest.class);
        assertThat(legacy).isEqualTo(explicitNull);
        assertThat(legacy.due()).isEqualTo(Instant.parse("2026-07-20T09:00:00Z"));
        assertThat(legacy.dueText()).isNull();
        assertThat(mapper.writeValueAsString(legacy)).doesNotContain("due_text");
        var blank = new MeetingAnalysisActionIngest("action", null, null, " \t ");
        assertThat(blank.dueText()).isNull();
        assertThat(blank.due()).isNull();
    }

    @Test
    void sourcePhraseIsExactAndLimitMatchesExistingUtf16FieldValidation() {
        String phrase = " Perşembe günü ";
        var action = new MeetingAnalysisActionIngest("action", null, null, phrase);
        assertThat(action.dueText()).isEqualTo(phrase);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String valid : new String[]{"x".repeat(255), "\uD83D\uDE80".repeat(127) + "x"}) {
                assertThat(validator.validate(new MeetingAnalysisActionIngest("action", null, null, valid)))
                        .isEmpty();
            }
            for (String invalid : new String[]{"x".repeat(256), "\uD83D\uDE80".repeat(128)}) {
                assertThat(validator.validate(new MeetingAnalysisActionIngest("action", null, null, invalid)))
                        .singleElement().satisfies(violation ->
                                assertThat(violation.getPropertyPath().toString()).isEqualTo("dueText"));
            }
        }
    }
}
