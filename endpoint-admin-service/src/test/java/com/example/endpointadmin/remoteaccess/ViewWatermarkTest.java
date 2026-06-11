package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.ViewWatermark.RenderSpec;
import com.example.endpointadmin.remoteaccess.ViewWatermark.WatermarkSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 D-5 — {@link ViewWatermark} VIEW_ONLY watermark spec: keyed, KVKK-privacy, deterministic, fail-closed. */
class ViewWatermarkTest {

    private static final byte[] KEY = "test-recorder-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private final ViewWatermark wm = new ViewWatermark(KEY, "k1");
    private final long start = Instant.parse("2026-06-12T00:00:30Z").toEpochMilli();

    @Test
    void sameInputsYieldTheSameSpecDeterministically() {
        assertEquals(wm.specFor("op-7", "sess-1", start), wm.specFor("op-7", "sess-1", start));
    }

    @Test
    void distinctOperatorOrSessionYieldsADistinctFingerprint() {
        WatermarkSpec a = wm.specFor("op-7", "sess-1", start);
        WatermarkSpec b = wm.specFor("op-8", "sess-1", start);
        WatermarkSpec c = wm.specFor("op-7", "sess-2", start);
        assertNotEquals(a.auditFingerprint(), b.auditFingerprint());
        assertNotEquals(a.auditFingerprint(), c.auditFingerprint());
        assertNotEquals(a.visibleLabel(), b.visibleLabel());
    }

    @Test
    void theVisibleLabelCarriesNoOperatorPii() {
        WatermarkSpec s = wm.specFor("zeynep@example.com", "sess-1", start);
        assertFalse(s.visibleLabel().contains("zeynep"));
        assertFalse(s.visibleLabel().contains("example"));
        assertTrue(s.visibleLabel().startsWith("RA-"));
    }

    @Test
    void theVisibleLabelShowsACoarseUtcMinuteWhileTheSpecKeepsTheExactStart() {
        WatermarkSpec s = wm.specFor("op-7", "sess-1", start); // 2026-06-12T00:00:30Z
        assertTrue(s.visibleLabel().endsWith("2026-06-12 00:00 UTC"), s.visibleLabel()); // seconds truncated
        assertEquals(start, s.sessionStartEpochMillis());                                 // exact retained
    }

    @Test
    void aBlankOperatorOrSessionDegradesToUnattributedButStillDistinct() {
        WatermarkSpec noOp = wm.specFor("  ", "sess-1", start);
        WatermarkSpec noSid = wm.specFor("op-7", null, start);
        assertFalse(noOp.attributed());
        assertFalse(noSid.attributed());
        assertTrue(noOp.visibleLabel().startsWith("RA-UNATTR-"));
        // still distinguishable (a different session under unattributed yields a different fingerprint)
        assertNotEquals(noOp.auditFingerprint(), wm.specFor("  ", "sess-2", start).auditFingerprint());
        assertTrue(wm.specFor("op-7", "sess-1", start).attributed());
    }

    @Test
    void theFingerprintIsKeyed() {
        ViewWatermark other = new ViewWatermark("a-different-recorder-key-99887766".getBytes(StandardCharsets.UTF_8), "k2");
        assertNotEquals(wm.specFor("op-7", "sess-1", start).auditFingerprint(),
                other.specFor("op-7", "sess-1", start).auditFingerprint());
        // same key -> stable
        ViewWatermark sameKey = new ViewWatermark(KEY, "k1");
        assertEquals(wm.specFor("op-7", "sess-1", start).auditFingerprint(),
                sameKey.specFor("op-7", "sess-1", start).auditFingerprint());
    }

    @Test
    void theCanonicalSpecFingerprintChangesWhenAnyFieldChanges() {
        WatermarkSpec base = wm.specFor("op-7", "sess-1", start);
        // a different render spec -> a different canonical fingerprint (tamper/contract surface)
        ViewWatermark dimmer = new ViewWatermark(KEY, "k1", new RenderSpec("TILED_DIAGONAL", 0.30, 1.0, 240));
        assertNotEquals(base.canonicalSpecFingerprint(),
                dimmer.specFor("op-7", "sess-1", start).canonicalSpecFingerprint());
        // stable for the same spec
        assertEquals(base.canonicalSpecFingerprint(), wm.specFor("op-7", "sess-1", start).canonicalSpecFingerprint());
    }

    @Test
    void everySpecFieldIsPopulatedSoTheTransportNeverFallsBack() {
        WatermarkSpec s = wm.specFor("op-7", "sess-1", start);
        assertEquals(ViewWatermark.VERSION, s.version());
        assertEquals("k1", s.keyId());
        assertTrue(s.visibleLabel() != null && !s.visibleLabel().isBlank());
        assertEquals(ViewWatermark.AUDIT_FP_HEX_LEN, s.auditFingerprint().length());
        assertTrue(s.bannerText() != null && !s.bannerText().isBlank());
        assertTrue(s.render() != null && !s.render().layout().isBlank());
        assertTrue(s.render().opacity() > 0 && s.render().tileSpacing() > 0);
        assertEquals(64, s.canonicalSpecFingerprint().length()); // full SHA-256 hex
    }

    @Test
    void constructionFailsFastOnAMissingKeyOrKeyId() {
        assertThrows(IllegalArgumentException.class, () -> new ViewWatermark(null, "k1"));
        assertThrows(IllegalArgumentException.class, () -> new ViewWatermark(new byte[0], "k1"));
        assertThrows(IllegalArgumentException.class, () -> new ViewWatermark(KEY, "  "));
        assertThrows(IllegalArgumentException.class, () -> new ViewWatermark(KEY, "k1", null));
    }
}
