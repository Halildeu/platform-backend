package com.example.ethics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.example.ethics.api.EthicsDtos.ReporterIdentityPayload;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * ES-212 (#3370) — the idempotency fingerprint over an identity whose optional fields are
 * absent.
 *
 * <p>Written after the live failure it describes: a confidential report carrying only a
 * name — the ordinary case, since e-mail, phone and unit are all optional — threw
 * {@code NullPointerException} inside the fingerprint before reaching any validation, and
 * the reporter saw "İşlem tamamlanamadı." The unit tests passed because none of them went
 * through {@code createReport}; the payload tests constructed identities with nulls but
 * exercised only the sealing path.
 */
class IdentityFingerprintTest {

    /** The two helpers are private and deliberately so; the behaviour is what is pinned. */
    private static String canonicalOptional(String value) throws Exception {
        Method m = EthicsService.class.getDeclaredMethod("canonicalOptional", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value);
    }

    @Test
    void anAbsentFieldDoesNotThrow() throws Exception {
        assertEquals("-", canonicalOptional(null));
    }

    @Test
    void absentAndEmptyDoNotHashAlike() throws Exception {
        // "e-posta verilmedi" and "e-posta boş gönderildi" are different submissions. If
        // they canonicalised the same, a retry that dropped a field would replay as the
        // original receipt instead of colliding — and the reporter would be told their
        // corrected report was already filed.
        assertNotEquals(canonicalOptional(null), canonicalOptional(""));
        assertEquals("0:", canonicalOptional(""));
    }

    @Test
    void aPresentFieldKeepsItsLengthPrefix() throws Exception {
        // The prefix is what makes the concatenation unambiguous when a user-controlled
        // field contains a colon, a newline, or the marker itself.
        assertEquals("5:a-b-c", canonicalOptional("a-b-c"));
        assertEquals("1:-", canonicalOptional("-"));
        assertNotEquals(canonicalOptional("-"), canonicalOptional(null));
    }

    @Test
    void theOrdinaryConfidentialSubmissionIsNameOnly() {
        // The shape the live failure arrived in: the form sends undefined for blank
        // optional fields on purpose, so "not given" stays distinguishable from "given as
        // empty" — which means four of the five fields are null on a typical report.
        ReporterIdentityPayload nameOnly =
                new ReporterIdentityPayload("Ayşe Yılmaz", null, null, null, null);
        assertEquals("Ayşe Yılmaz", nameOnly.fullName());
        assertEquals(null, nameOnly.email());
    }
}
