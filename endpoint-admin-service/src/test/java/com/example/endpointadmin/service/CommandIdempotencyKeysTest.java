package com.example.endpointadmin.service;

import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the {@code idempotency_key} column bound in code rather than in review
 * comments (platform-backend#921).
 *
 * <p>Before this suite existed the budget arithmetic lived in prose — "Fixed
 * prefix = 90 chars; body MUST fit 38 chars" — and the {@code admin-update-agent}
 * family shipped 129 characters into a {@code VARCHAR(128)} column, so every
 * UI-driven agent update returned HTTP 500. These tests fail on the shape, not on
 * the symptom, so a future prefix rename cannot reintroduce it.
 */
class CommandIdempotencyKeysTest {

    private static final UUID DEVICE = UUID.fromString("423b6fc3-7497-4083-bd2f-5e2fe543bfe9");
    private static final UUID SCOPE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** Every fixed part the service layer assembles, with its caller-key cap. */
    static Stream<Object[]> keyShapes() {
        String longestCommandType = Arrays.stream(CommandType.values())
                .map(Enum::name)
                .max(java.util.Comparator.comparingInt(String::length))
                .orElseThrow();
        return Stream.of(
                new Object[] {"admin-install:" + DEVICE + ":" + SCOPE + ":", 40},
                new Object[] {"admin-update-agent:" + DEVICE + ":" + SCOPE + ":", 31},
                new Object[] {"admin-local-password:" + DEVICE + ":", 40},
                new Object[] {"admin-uninstall:" + DEVICE + ":" + SCOPE + ":", 38},
                new Object[] {"admin:" + DEVICE + ":" + longestCommandType + ":", 0});
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("keyShapes")
    @DisplayName("generated key fits the column when the caller supplies nothing")
    void generatedKeyFitsColumn(String fixedPart, int callerKeyCap) {
        // The frontend deliberately posts no idempotencyKey (the agent-update
        // body is exactly {releaseId, reason}), so this is the production shape.
        String key = CommandIdempotencyKeys.build(fixedPart, null, callerKeyCap);

        assertThat(key)
                .as("generated key for %s", fixedPart)
                .startsWith(fixedPart)
                .hasSizeLessThanOrEqualTo(CommandIdempotencyKeys.MAX_LENGTH);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("keyShapes")
    @DisplayName("key fits the column for a pathologically long caller key")
    void oversizedCallerKeyFitsColumn(String fixedPart, int callerKeyCap) {
        String key = CommandIdempotencyKeys.build(fixedPart, "x".repeat(4096), callerKeyCap);

        assertThat(key).hasSizeLessThanOrEqualTo(CommandIdempotencyKeys.MAX_LENGTH);
    }

    @Test
    @DisplayName("regression: the agent-update shape used to assemble 129 chars")
    void agentUpdateGeneratedKeyNoLongerOverflows() {
        String fixedPart = "admin-update-agent:" + DEVICE + ":" + SCOPE + ":";

        // The pre-fix expression, reproduced literally.
        String legacy = fixedPart + UUID.randomUUID();
        assertThat(legacy)
                .as("the shape this test exists to prevent")
                .hasSize(129);

        assertThat(CommandIdempotencyKeys.build(fixedPart, null, 31))
                .hasSizeLessThanOrEqualTo(CommandIdempotencyKeys.MAX_LENGTH);
    }

    @Test
    @DisplayName("keys that already fit are returned byte for byte")
    void shortKeysAreUnchanged() {
        // Everything that could be persisted before this change must keep
        // producing the same string, or idempotent replay of an existing row
        // would silently mint a duplicate command.
        assertThat(CommandIdempotencyKeys.build("admin-install:" + DEVICE + ":" + SCOPE + ":",
                "operator-supplied-key", 40))
                .isEqualTo("admin-install:" + DEVICE + ":" + SCOPE + ":operator-supplied-key");

        assertThat(CommandIdempotencyKeys.build("admin-update-agent:" + DEVICE + ":" + SCOPE + ":",
                "ui-retry-0001", 31))
                .isEqualTo("admin-update-agent:" + DEVICE + ":" + SCOPE + ":ui-retry-0001");
    }

    @Test
    @DisplayName("collapsing is deterministic so idempotent replay still matches")
    void collapsedKeysAreDeterministic() {
        String fixedPart = "admin-update-agent:" + DEVICE + ":" + SCOPE + ":";
        String oversized = "y".repeat(400);

        assertThat(CommandIdempotencyKeys.build(fixedPart, oversized, 31))
                .isEqualTo(CommandIdempotencyKeys.build(fixedPart, oversized, 31));
    }

    @Test
    @DisplayName("a caller key over the column bound is collapsed, not rejected")
    void boundCollapsesOversizedCallerKey() {
        assertThat(CommandIdempotencyKeys.bound("short-key")).isEqualTo("short-key");
        assertThat(CommandIdempotencyKeys.bound("z".repeat(500)))
                .hasSize(CommandIdempotencyKeys.HASH_LENGTH);
    }

    @Test
    @DisplayName("a prefix with no room for a body fails at the call site, not in PostgreSQL")
    void impossiblePrefixFailsFast() {
        assertThatThrownBy(() -> CommandIdempotencyKeys.build("p".repeat(120), null, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("leaves no room");
    }

    @Test
    @DisplayName("MAX_LENGTH tracks the JPA column so schema drift cannot go unnoticed")
    void columnLengthMatchesConstant() throws NoSuchFieldException {
        Field field = EndpointCommand.class.getDeclaredField("idempotencyKey");
        Column column = field.getAnnotation(Column.class);

        assertThat(column).as("@Column on EndpointCommand.idempotencyKey").isNotNull();
        assertThat(column.length())
                .as("CommandIdempotencyKeys.MAX_LENGTH must match the mapped column width")
                .isEqualTo(CommandIdempotencyKeys.MAX_LENGTH);
    }
}
