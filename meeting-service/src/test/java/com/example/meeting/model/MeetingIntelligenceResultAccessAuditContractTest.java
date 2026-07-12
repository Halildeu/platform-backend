package com.example.meeting.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MeetingIntelligenceResultAccessAuditContractTest {

    private static final Set<String> ALLOWED_COLUMNS = Set.of(
            "id",
            "tenant_id",
            "org_id",
            "accessor_subject",
            "meeting_id",
            "analysis_run_id",
            "access_type",
            "result_count",
            "trace_id",
            "accessed_at");
    private static final Set<String> FORBIDDEN_CONTENT_TOKENS = Set.of(
            "summary", "transcript", "decision", "action", "citation", "source",
            "prompt", "request_body", "response_body", "payload", "json", "text");

    @Test
    void entityColumnAllowlistCannotHoldMeetingIntelligenceContent() {
        Set<String> columns = Arrays.stream(MeetingIntelligenceResultAccessAudit.class.getDeclaredFields())
                .map(field -> field.getAnnotation(Column.class))
                .filter(annotation -> annotation != null)
                .map(Column::name)
                .collect(Collectors.toSet());

        assertThat(columns).isEqualTo(ALLOWED_COLUMNS);
        assertThat(columns).noneMatch(MeetingIntelligenceResultAccessAuditContractTest::forbidden);
    }

    @Test
    void migrationCreateTableColumnListIsContentFree() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V5__meeting_intelligence_result_access_audit.sql"),
                StandardCharsets.UTF_8);
        String tableBlock = sql.substring(
                sql.indexOf("CREATE TABLE meeting_intelligence_result_access_audit"),
                sql.indexOf("CREATE INDEX idx_meeting_result_access_tenant_accessed"));

        for (String token : FORBIDDEN_CONTENT_TOKENS) {
            assertThat(tableBlock)
                    .as("audit table must not define a %s content column", token)
                    .doesNotContainPattern("(?m)^\\s*" + token + "[a-z0-9_]*\\s+");
        }
    }

    @Test
    void onlyExplicitlyAllowlistedStringFieldsExist() {
        Set<String> stringFields = Arrays.stream(MeetingIntelligenceResultAccessAudit.class.getDeclaredFields())
                .filter(field -> field.getType().equals(String.class))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(stringFields).containsExactlyInAnyOrder(
                "accessorSubject", "traceId");
    }

    private static boolean forbidden(String column) {
        return FORBIDDEN_CONTENT_TOKENS.stream().anyMatch(column::contains);
    }
}
