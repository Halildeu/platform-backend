package com.example.ethics;

import com.example.ethics.api.EthicsDtos.CaseDetail;
import com.example.ethics.api.EthicsDtos.CaseSummary;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 — the detail response must carry everything the list response carries (#991).
 *
 * <p>The client declares {@code EthicsCaseDetail extends EthicsCaseSummary}, which asserts
 * the detail is a superset of the summary. Nothing enforced it. The two records were
 * written independently and drifted: {@code createdAt}, {@code updatedAt} and
 * {@code participantCount} were on the list and absent from the detail.
 *
 * <p>The cost was not theoretical. The manager computes the acknowledgement deadline from
 * {@code createdAt}; with the field missing the parse yielded NaN and the screen reported
 * "Alındı teyidi durumu okunamadı" — on a case that had been acknowledged sixteen seconds
 * after it was filed. The response was cast client-side, so TypeScript could not see it,
 * and unit tests could not either: their fixtures supply a {@code createdAt} the real
 * response never sends. Only opening the screen showed it.
 *
 * <p>This test is deliberately structural rather than example-based. A test that checked
 * one field would pass the day someone adds the next one to {@code CaseSummary} alone.
 */
class CaseDetailFieldParityTest {

    /**
     * Fields the summary carries that the detail deliberately does not need.
     *
     * <p>Empty, and adding to it should feel expensive: every entry is a place where the
     * client's {@code extends} becomes a lie again. If a genuine exception ever arises,
     * the client type must stop claiming inheritance on the same commit.
     */
    private static final Set<String> DELIBERATELY_ABSENT = Set.of();

    @Test
    @DisplayName("vaka detayı, listenin taşıdığı her alanı taşır")
    void theDetailCarriesEveryFieldTheSummaryCarries() {
        Map<String, Class<?>> summary = componentsOf(CaseSummary.class);
        Map<String, Class<?>> detail = componentsOf(CaseDetail.class);

        var missing = summary.keySet().stream()
                .filter(name -> !detail.containsKey(name))
                .filter(name -> !DELIBERATELY_ABSENT.contains(name))
                .sorted()
                .toList();

        Assertions.assertThat(missing)
                .as("CaseSummary'de olup CaseDetail'de olmayan alanlar — istemci "
                        + "EthicsCaseDetail extends EthicsCaseSummary diyor, bu iddia tutmalı")
                .isEmpty();
    }

    /**
     * Same name, same type. A field present under the same name but a different type is a
     * subtler version of the same defect: the client reads it as the summary's type.
     */
    @Test
    @DisplayName("ortak alanların tipleri de aynı")
    void sharedFieldsAgreeOnType() {
        Map<String, Class<?>> summary = componentsOf(CaseSummary.class);
        Map<String, Class<?>> detail = componentsOf(CaseDetail.class);

        var mismatched = summary.entrySet().stream()
                .filter(entry -> detail.containsKey(entry.getKey()))
                .filter(entry -> !detail.get(entry.getKey()).equals(entry.getValue()))
                .map(entry -> entry.getKey() + ": liste=" + entry.getValue().getSimpleName()
                        + " detay=" + detail.get(entry.getKey()).getSimpleName())
                .sorted()
                .toList();

        Assertions.assertThat(mismatched).as("aynı adlı alanların tipleri ayrışmış").isEmpty();
    }

    private static Map<String, Class<?>> componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType,
                        (first, ignored) -> first, LinkedHashMap::new));
    }
}
