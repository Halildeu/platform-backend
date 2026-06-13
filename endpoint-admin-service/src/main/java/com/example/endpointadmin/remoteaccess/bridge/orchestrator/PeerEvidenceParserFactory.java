package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Faz 22.6 D10.1 (#634, Codex 019ec29a) — selects the {@link PeerEvidenceParser} with a blocking matrix at
 * construction (= bean creation = STARTUP fail-fast):
 * <ul>
 *   <li><b>FAIL_CLOSED</b> — the fail-closed default ({@link PeerEvidenceParser#FAIL_CLOSED}): empty evidence ⇒
 *       every trust false ⇒ the broker never PERMITs. The current behaviour until the pilot opts in.</li>
 *   <li><b>TRANSPORT_BOUND</b> — the {@link TransportBoundPeerEvidenceParser}: real CertRef from the mTLS
 *       transport leaf + the agent's SLSA attestation in the pilot canonical form. FORBIDDEN in a production-like
 *       profile — the pilot attestation wire-form is synthetic-agent-specific; a production parser must speak the
 *       real agent's attestation format. (The parser produces only evidence; the verifiers still decide trust.)</li>
 * </ul>
 */
public final class PeerEvidenceParserFactory {

    private static final Logger log = LoggerFactory.getLogger(PeerEvidenceParserFactory.class);

    public enum ParserType { FAIL_CLOSED, TRANSPORT_BOUND }

    private PeerEvidenceParserFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access peer-evidence parser config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * Config-string entry point (the {@code remote-bridge.peer-evidence.parser} value): a blank/unset value is the
     * safe {@code FAIL_CLOSED} default; an unknown value is REJECTED fail-fast in the matrix's own voice (so an
     * invalid config can never fail OPEN). Case/space-insensitive.
     */
    public static PeerEvidenceParser create(String configuredType, boolean productionLikeProfile) {
        String raw = configuredType == null ? "" : configuredType.strip();
        ParserType type;
        if (raw.isEmpty()) {
            type = ParserType.FAIL_CLOSED; // unset config → the safe default
        } else {
            try {
                type = ParserType.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw reject("unknown peer-evidence parser type '" + raw
                        + "' (expected FAIL_CLOSED|TRANSPORT_BOUND)");
            }
        }
        return create(type, productionLikeProfile);
    }

    /**
     * @param productionLikeProfile when true, the pilot TRANSPORT_BOUND parser is REFUSED
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static PeerEvidenceParser create(ParserType type, boolean productionLikeProfile) {
        ParserType t = type == null ? ParserType.FAIL_CLOSED : type; // fail-closed default
        switch (t) {
            case FAIL_CLOSED -> {
                return PeerEvidenceParser.FAIL_CLOSED;
            }
            case TRANSPORT_BOUND -> {
                if (productionLikeProfile) {
                    throw reject("peer-evidence parser TRANSPORT_BOUND uses the pilot (synthetic-agent) "
                            + "attestation wire-form and is forbidden in a production-like profile — a prod parser "
                            + "must speak the real agent's attestation format");
                }
                return new TransportBoundPeerEvidenceParser();
            }
            default -> throw reject("unreachable peer-evidence parser type " + t);
        }
    }
}
