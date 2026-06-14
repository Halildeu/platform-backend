package com.example.endpointadmin.tpmattest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Faz 22.3B (ADR-0039) gate-4a-2.4 — verifier <b>V6</b> (PCR policy), design §4 + hardening
 * T-5 (exact PCR subset) / T-6 (per-risk-class golden allow-set). Runs AFTER V5 has already
 * verified the quote signature + nonce; V6 judges only the PCR <i>content</i>.
 *
 * <ul>
 *   <li><b>T-5</b>: the quote's PCR selection must EQUAL the required selection as a
 *       {@code (hashAlg, bitmap)} tuple-set (order-independent; duplicate banks rejected) — not a
 *       superset/subset substitution.</li>
 *   <li><b>T-6</b>: the aggregate {@code pcrDigest} must be a member of the configured golden
 *       allow-set for the device's risk class. Fail-closed: an EMPTY allow-set denies (an operator
 *       may opt into selection-only "advisory" mode explicitly, which is WARN-logged).</li>
 * </ul>
 * Every failure → {@link TpmDenyCode#PCR_POLICY_FAILED} (audit-only; uniform 403 at gate-4d).
 */
public final class TpmPcrPolicy {

    private static final Logger log = LoggerFactory.getLogger(TpmPcrPolicy.class);

    private final Set<String> requiredSelectionKeys;
    private final Set<String> allowedDigests; // lowercase hex
    private final boolean advisoryWhenNoAllowSet;

    public TpmPcrPolicy(Set<TpmsAttest.PcrSelection> requiredSelection,
                        Set<String> allowedDigestsHex,
                        boolean advisoryWhenNoAllowSet) {
        if (requiredSelection == null || requiredSelection.isEmpty()) {
            throw new IllegalArgumentException("required PCR selection must be non-empty");
        }
        this.requiredSelectionKeys = requiredSelection.stream()
                .map(TpmsAttest.PcrSelection::key).collect(Collectors.toUnmodifiableSet());
        this.allowedDigests = allowedDigestsHex == null ? Set.of()
                : allowedDigestsHex.stream().map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        this.advisoryWhenNoAllowSet = advisoryWhenNoAllowSet;
    }

    public void verify(TpmsAttest quote) {
        if (quote == null || !quote.isQuote()) {
            throw deny("attestation is not a TPM2_Quote");
        }
        List<TpmsAttest.PcrSelection> sels = quote.pcrSelections();
        Set<String> got = sels.stream().map(TpmsAttest.PcrSelection::key).collect(Collectors.toSet());
        if (got.size() != sels.size()) {
            throw deny("duplicate PCR bank in selection");
        }
        if (!got.equals(requiredSelectionKeys)) {
            throw deny("PCR selection mismatch (got " + got + ", required " + requiredSelectionKeys + ")");
        }
        if (allowedDigests.isEmpty()) {
            if (advisoryWhenNoAllowSet) {
                log.warn("Faz22.3B V6: no PCR allow-set configured — advisory override active, selection-only (NOT pinned)");
                return;
            }
            throw deny("no PCR allow-set configured for this risk class (fail-closed)");
        }
        String digestHex = HexFormat.of().formatHex(quote.pcrDigest()).toLowerCase(Locale.ROOT);
        if (!allowedDigests.contains(digestHex)) {
            throw deny("pcrDigest not in the golden allow-set");
        }
    }

    private static TpmAttestException deny(String detail) {
        return new TpmAttestException(TpmDenyCode.PCR_POLICY_FAILED, detail);
    }
}
