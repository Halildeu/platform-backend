package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleRequest;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-025 — cross-field validation tests for
 * {@link ProhibitedSoftwareRuleValidator} (Faz 22.5). These mirror the V19
 * DB CHECK constraints so the operator gets a clean 400 instead of a 500.
 */
class ProhibitedSoftwareRuleValidatorTest {

    private final ProhibitedSoftwareRuleValidator validator =
            new ProhibitedSoftwareRuleValidator();

    private static ProhibitedSoftwareRuleRequest req(
            ProhibitedSoftwareMatchType type, ProhibitedSoftwareMatchMode mode,
            String name, String publisher) {
        return new ProhibitedSoftwareRuleRequest(type, mode, name, publisher, true, null);
    }

    @Test
    void validNameRulePasses() {
        assertThatCode(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void nameRuleWithPublisherRejected() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", "Vendor")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherPattern must be absent");
    }

    @Test
    void nameRuleWithoutNameRejected() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namePattern is required");
    }

    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "   ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namePattern is required");
    }

    @Test
    void publisherRuleWithoutPublisherRejected() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.PUBLISHER,
                        ProhibitedSoftwareMatchMode.EXACT, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherPattern is required");
    }

    @Test
    void nameAndPublisherRequiresBoth() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME_AND_PUBLISHER,
                        ProhibitedSoftwareMatchMode.EXACT, "vpn", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherPattern is required");
    }

    @Test
    void containsBelowMinLengthRejected() {
        // "ab" trimmed length 2 < MIN_CONTAINS_LENGTH (3)
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.CONTAINS, "ab", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least " + ProhibitedSoftwareRuleValidator.MIN_CONTAINS_LENGTH);
    }

    @Test
    void containsAtMinLengthPasses() {
        assertThatCode(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.CONTAINS, "vpn", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void exactShortNameAllowed() {
        // EXACT is exempt from the CONTAINS min length — an exact 2-char name
        // is legitimate.
        assertThatCode(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "qq", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void normalizeIsLowerTrimLocaleRoot() {
        assertThat(ProhibitedSoftwareRuleValidator.normalize("  uTorrent  "))
                .isEqualTo("utorrent");
        assertThat(ProhibitedSoftwareRuleValidator.normalize(null)).isEmpty();
        assertThat(ProhibitedSoftwareRuleValidator.normalize("   ")).isEmpty();
    }

    @Test
    void trimToNullCollapsesBlank() {
        assertThat(ProhibitedSoftwareRuleValidator.trimToNull("  x ")).isEqualTo("x");
        assertThat(ProhibitedSoftwareRuleValidator.trimToNull("   ")).isNull();
        assertThat(ProhibitedSoftwareRuleValidator.trimToNull(null)).isNull();
    }
}
