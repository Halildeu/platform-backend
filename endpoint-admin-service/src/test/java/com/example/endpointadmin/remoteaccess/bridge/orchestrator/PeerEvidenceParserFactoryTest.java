package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParserFactory.ParserType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 D10.1 (#634, Codex 019ec29a) — the {@link PeerEvidenceParserFactory} blocking matrix. The default
 * and FAIL_CLOSED both yield the fail-closed parser (allowed in every profile — it is the SAFE one); the
 * TRANSPORT_BOUND pilot parser is allowed only in a non-prod profile and is REFUSED fail-fast in a prod-like
 * profile (its attestation wire-form is synthetic-agent-specific).
 */
class PeerEvidenceParserFactoryTest {

    @Test
    void nullTypeDefaultsToTheFailClosedParser() {
        assertSame(PeerEvidenceParser.FAIL_CLOSED, PeerEvidenceParserFactory.create((ParserType) null, false));
        assertSame(PeerEvidenceParser.FAIL_CLOSED, PeerEvidenceParserFactory.create((ParserType) null, true));
    }

    @Test
    void failClosedIsTheFailClosedParserInEveryProfile() {
        assertSame(PeerEvidenceParser.FAIL_CLOSED,
                PeerEvidenceParserFactory.create(ParserType.FAIL_CLOSED, false));
        // the safe default is allowed even in prod — it grants no evidence, so it can never widen trust
        assertSame(PeerEvidenceParser.FAIL_CLOSED,
                PeerEvidenceParserFactory.create(ParserType.FAIL_CLOSED, true));
    }

    @Test
    void transportBoundIsTheRealParserInNonProd() {
        assertInstanceOf(TransportBoundPeerEvidenceParser.class,
                PeerEvidenceParserFactory.create(ParserType.TRANSPORT_BOUND, false));
    }

    @Test
    void theConfigStringEntryPointIsCaseAndSpaceInsensitiveAndFailClosed() {
        // blank / null config → the safe FAIL_CLOSED default (never fail-open)
        assertSame(PeerEvidenceParser.FAIL_CLOSED, PeerEvidenceParserFactory.create("", false));
        assertSame(PeerEvidenceParser.FAIL_CLOSED, PeerEvidenceParserFactory.create((String) null, false));
        assertSame(PeerEvidenceParser.FAIL_CLOSED, PeerEvidenceParserFactory.create("  fail_closed  ", true));
        // case/space-insensitive opt-in
        assertInstanceOf(TransportBoundPeerEvidenceParser.class,
                PeerEvidenceParserFactory.create("transport_bound", false));
        // an UNKNOWN config value is rejected fail-fast (not silently defaulted, not fail-open)
        assertThrows(IllegalStateException.class, () -> PeerEvidenceParserFactory.create("BOGUS", false));
        // the prod-forbid still applies through the string entry point
        assertThrows(IllegalStateException.class,
                () -> PeerEvidenceParserFactory.create("transport_bound", true));
    }

    @Test
    void transportBoundIsRefusedInAProdLikeProfile() {
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> PeerEvidenceParserFactory.create(ParserType.TRANSPORT_BOUND, true));
        // the rejection names the production-forbidden parser (synthetic-agent wire-form)
        org.junit.jupiter.api.Assertions.assertTrue(
                rejected.getMessage().contains("TRANSPORT_BOUND"), "the rejection names the parser type");
    }
}
