package com.example.ethics.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-301A — the lifecycle as a frozen contract.
 *
 * <p>These are not tests of the implementation so much as a written-down statement of
 * which moves exist, kept somewhere a reviewer can read it. The previous vocabulary was
 * a {@code Set.of(...)} literal inline in the one method that happened to write status,
 * so "can a closed case go back to new?" could only be answered by reading control flow —
 * and the answer was yes, silently.
 */
class CaseLifecycleContractTest {

    @Test
    @DisplayName("statü sözlüğü ISO 37002 aşamalarını karşılar ve genişlemez")
    void statusVocabularyIsFrozen() {
        assertThat(CaseLifecycle.STATUSES)
                .containsExactlyInAnyOrder("NEW", "ASSESSING", "INVESTIGATING", "CLOSED");
    }

    @Test
    @DisplayName("sonuç kümesi kapalıdır")
    void outcomeVocabularyIsFrozen() {
        assertThat(CaseLifecycle.OUTCOMES).containsExactlyInAnyOrder(
                "SUBSTANTIATED", "PARTIALLY_SUBSTANTIATED", "UNSUBSTANTIATED",
                "OUT_OF_SCOPE", "REFERRED", "WITHDRAWN");
    }

    @Test
    @DisplayName("izinli geçişler tam olarak listelenenlerdir")
    void transitionRegistryIsExact() {
        assertThat(CaseLifecycle.allowedTransitions()).containsExactlyInAnyOrder(
                "NEW->ASSESSING", "NEW->CLOSED",
                "ASSESSING->INVESTIGATING", "ASSESSING->CLOSED",
                "INVESTIGATING->CLOSED",
                "CLOSED->ASSESSING");
    }

    /**
     * The specific move that used to be possible. Reopening as {@code NEW} would present a
     * case that has already been investigated and concluded as though nothing had happened
     * to it — and the finding it was closed on would go with it.
     */
    @Test
    @DisplayName("kapalı dava NEW'e döndürülemez; yeniden açma yalnız ASSESSING'edir")
    void closedCannotBeSentBackToNew() {
        assertThat(CaseLifecycle.isTransitionAllowed("CLOSED", "NEW")).isFalse();
        assertThat(CaseLifecycle.isTransitionAllowed("CLOSED", "INVESTIGATING")).isFalse();
        assertThat(CaseLifecycle.isTransitionAllowed("CLOSED", "ASSESSING")).isTrue();
        assertThat(CaseLifecycle.isReopen("CLOSED", "ASSESSING")).isTrue();
        assertThat(CaseLifecycle.isReopen("NEW", "ASSESSING")).isFalse();
    }

    @Test
    @DisplayName("geriye dönük aşama atlaması yasak")
    void lifecycleDoesNotRunBackwards() {
        assertThat(CaseLifecycle.isTransitionAllowed("INVESTIGATING", "ASSESSING")).isFalse();
        assertThat(CaseLifecycle.isTransitionAllowed("ASSESSING", "NEW")).isFalse();
        assertThat(CaseLifecycle.isTransitionAllowed("NEW", "INVESTIGATING")).isFalse();
    }

    /**
     * Idempotent no-ops are fine everywhere except on a closed case, where re-closing would
     * be a way to swap one finding for another with no reopening in the record.
     */
    @Test
    @DisplayName("aynı statüye yazma no-op'tur; kapalıda ise reddedilir")
    void sameStatusIsNoOpExceptOnClosed() {
        assertThat(CaseLifecycle.isTransitionAllowed("ASSESSING", "ASSESSING")).isTrue();
        assertThat(CaseLifecycle.isTransitionAllowed("NEW", "NEW")).isTrue();
        assertThat(CaseLifecycle.isTransitionAllowed("CLOSED", "CLOSED")).isFalse();
    }

    @Test
    @DisplayName("IN_REVIEW yalnız yazma alias'ıdır ve ASSESSING'e çözülür")
    void deprecatedAliasResolvesButIsNotAStatus() {
        assertThat(CaseLifecycle.canonicalStatus("IN_REVIEW")).isEqualTo("ASSESSING");
        assertThat(CaseLifecycle.canonicalStatus("in_review")).isEqualTo("ASSESSING");
        assertThat(CaseLifecycle.STATUSES).doesNotContain("IN_REVIEW");
        assertThat(CaseLifecycle.DEPRECATED_WRITE_ALIASES).containsOnlyKeys("IN_REVIEW");
    }

    @Test
    @DisplayName("tanımsız statü ve sonuç null döner, sessizce kabul edilmez")
    void unknownValuesAreRejectedNotCoerced() {
        assertThat(CaseLifecycle.canonicalStatus("SUPERUSER")).isNull();
        assertThat(CaseLifecycle.canonicalStatus("")).isNull();
        assertThat(CaseLifecycle.canonicalStatus(null)).isNull();
        assertThat(CaseLifecycle.canonicalOutcome("MAYBE")).isNull();
        assertThat(CaseLifecycle.canonicalOutcome(null)).isNull();
        assertThat(CaseLifecycle.canonicalOutcome("substantiated")).isEqualTo("SUBSTANTIATED");
    }

    /**
     * The reporter mailbox types its status as a closed union with no fallback, so a value
     * it does not know renders blank rather than degrading. Every internal status must
     * therefore land on one of its three — asserted over the whole vocabulary rather than
     * over the statuses that happened to exist when the projection was written, so adding
     * a fifth status without deciding what a reporter should see fails here.
     */
    @Test
    @DisplayName("her iç statü ihbarcı projeksiyonunda bir karşılığa düşer")
    void everyStatusProjectsOntoTheReporterContract() {
        Set<String> reporterContract = Set.of("NEW", "IN_REVIEW", "CLOSED");
        for (String status : CaseLifecycle.STATUSES) {
            assertThat(CaseLifecycle.reporterVisibleStatus(status))
                    .as("iç statü '%s' ihbarcıya çevrilemiyor", status)
                    .isIn(reporterContract);
        }
    }

    @Test
    @DisplayName("ihbarcı projeksiyonu iç aşamaları gizler")
    void reporterProjectionHidesInternalStages() {
        assertThat(CaseLifecycle.reporterVisibleStatus("ASSESSING")).isEqualTo("IN_REVIEW");
        assertThat(CaseLifecycle.reporterVisibleStatus("INVESTIGATING")).isEqualTo("IN_REVIEW");
    }
}
