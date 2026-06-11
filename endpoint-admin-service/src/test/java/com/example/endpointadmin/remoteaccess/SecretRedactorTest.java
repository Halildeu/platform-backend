package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.SecretRedactor.Category;
import com.example.endpointadmin.remoteaccess.SecretRedactor.LabelMode;
import com.example.endpointadmin.remoteaccess.SecretRedactor.Profile;
import com.example.endpointadmin.remoteaccess.SecretRedactor.RedactionResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 D-4 — {@link SecretRedactor} secret/PII redaction for audit + operator-visible output. */
class SecretRedactorTest {

    private final SecretRedactor redactor = SecretRedactor.DEFAULT; // CREDENTIAL_AND_PII, CATEGORY

    @Test
    void credentialPatternsAreMaskedAndNeverLeakTheSecret() {
        RedactionResult pw = redactor.redactText("Server=db;Password=p@ssw0rd!;Trusted=no");
        assertFalse(pw.maskedText().contains("p@ssw0rd!"));
        assertTrue(pw.maskedText().contains("[REDACTED:CREDENTIAL]"));
        assertEquals(1, pw.hits().get(Category.CREDENTIAL));

        RedactionResult bearer = redactor.redactText("GET /x\nAuthorization: Bearer abc123.DEF456-ghi");
        assertFalse(bearer.maskedText().contains("abc123.DEF456-ghi"));
        assertEquals(1, bearer.hits().get(Category.CREDENTIAL));

        RedactionResult url = redactor.redactText("psql postgres://admin:s3cr3t@db.internal:5432/app");
        assertFalse(url.maskedText().contains("s3cr3t"));
        assertFalse(url.maskedText().contains("admin:s3cr3t"));
        assertTrue(url.maskedText().contains("@db.internal")); // host preserved, only userinfo masked

        // Basic auth anchored to the header (Codex add); the word "Basic" alone is NOT a false positive
        RedactionResult basic = redactor.redactText("Authorization: Basic dXNlcjpwYXNz");
        assertFalse(basic.maskedText().contains("dXNlcjpwYXNz"));
        assertEquals(1, basic.hits().get(Category.CREDENTIAL));
        assertEquals("Basic networking is fine here",
                redactor.redactText("Basic networking is fine here").maskedText()); // no FP on the word
    }

    @Test
    void keyMaterialPatternsAreMasked() {
        // gitleaks:allow — a FAKE JWT test fixture (header {"alg":"HS256"}, no real secret); the redactor MUST be tested against a JWT-shaped string
        String jwt = "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4f"; // gitleaks:allow
        RedactionResult r = redactor.redactText(jwt);
        assertFalse(r.maskedText().contains("eyJhbGci"));
        assertEquals(1, r.hits().get(Category.KEY_MATERIAL));

        RedactionResult aws = redactor.redactText("aws_key=AKIAIOSFODNN7EXAMPLE rest");
        assertFalse(aws.maskedText().contains("AKIAIOSFODNN7EXAMPLE"));
        assertEquals(1, aws.hits().get(Category.KEY_MATERIAL));

        RedactionResult sts = redactor.redactText("session ASIAIOSFODNN7EXAMPLE token"); // STS session key (Codex add)
        assertFalse(sts.maskedText().contains("ASIAIOSFODNN7EXAMPLE"));
        assertEquals(1, sts.hits().get(Category.KEY_MATERIAL));

        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";
        RedactionResult pemR = redactor.redactText(pem);
        assertFalse(pemR.maskedText().contains("MIIEowIBAAKCAQEA"));
        assertEquals(1, pemR.hits().get(Category.KEY_MATERIAL));
    }

    @Test
    void piiIsMaskedUnderThePiiProfile() {
        RedactionResult email = redactor.redactText("contact zeynep@example.com for help");
        assertFalse(email.maskedText().contains("zeynep@example.com"));
        assertEquals(1, email.hits().get(Category.PII));

        // a checksum-VALID TCKN is masked; an invalid 11-digit number is left alone (false-positive control)
        RedactionResult tckn = redactor.redactText("kimlik 10000000146 ve 11111111111 numaralari");
        assertFalse(tckn.maskedText().contains("10000000146"));   // valid -> masked
        assertTrue(tckn.maskedText().contains("11111111111"));    // invalid checksum -> NOT masked
        assertEquals(1, tckn.hits().get(Category.PII));

        RedactionResult iban = redactor.redactText("IBAN TR330006100519786457841326 hesap");
        assertFalse(iban.maskedText().contains("TR330006100519786457841326"));
        assertEquals(1, iban.hits().get(Category.PII));
    }

    @Test
    void cleanOutputIsUnchangedWithNoHits() {
        RedactionResult r = redactor.redactText("Active Connections\n  TCP 10.0.0.1:443  ESTABLISHED");
        assertEquals("Active Connections\n  TCP 10.0.0.1:443  ESTABLISHED", r.maskedText());
        assertEquals(0, r.total());
    }

    @Test
    void anOverlappingBearerJwtIsMaskedOnceAsCredentialNotTwice() {
        String jwtToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhYmMifQ.c2lnbmF0dXJlAAA"; // gitleaks:allow — FAKE JWT test fixture, no real secret
        RedactionResult r = redactor.redactText("Authorization: Bearer " + jwtToken);
        assertFalse(r.maskedText().contains("eyJhbGci"));
        assertEquals(1, r.hits().get(Category.CREDENTIAL));     // matched as a credential first
        assertEquals(0, r.total() - r.hits().get(Category.CREDENTIAL)); // no second (KEY_MATERIAL) hit
    }

    @Test
    void opaqueLabelModeHidesTheCategoryInTextButKeepsItInTheMetricChannel() {
        SecretRedactor opaque = new SecretRedactor(Profile.CREDENTIAL_AND_PII, LabelMode.OPAQUE);
        RedactionResult r = opaque.redactText("Password=hunter2");
        assertTrue(r.maskedText().contains("[REDACTED]"));
        assertFalse(r.maskedText().contains("[REDACTED:CREDENTIAL]"));
        assertFalse(r.maskedText().contains("hunter2"));
        assertEquals(1, r.hits().get(Category.CREDENTIAL));     // category survives in the metric channel
    }

    @Test
    void theCredentialOnlyProfileLeavesPiiAlone() {
        SecretRedactor credOnly = new SecretRedactor(Profile.CREDENTIAL_ONLY, LabelMode.CATEGORY);
        RedactionResult r = credOnly.redactText("mail zeynep@example.com pass Password=x");
        assertTrue(r.maskedText().contains("zeynep@example.com")); // PII off
        assertFalse(r.maskedText().contains("Password=x"));        // credential still masked
        assertEquals(1, r.hits().get(Category.CREDENTIAL));
        assertEquals(null, r.hits().get(Category.PII));
    }

    @Test
    void redactTextIsFailClosedForNullAndOversize() {
        assertEquals("", redactor.redactText(null).maskedText());
        RedactionResult big = redactor.redactText("a".repeat(SecretRedactor.MAX_LEN + 1));
        assertEquals("[REDACTED]", big.maskedText());             // oversize -> fully masked, never scanned-raw
    }

    @Test
    void structuredRedactionMasksTheValueAfterASensitiveFlag() {
        // policy-agnostic: the caller passes the sensitive flag set (e.g. policy.sensitiveValueFlags(command))
        assertEquals("connect host /p [REDACTED:CREDENTIAL] /v",
                redactor.redactCommandLine("connect host /p s3cr3t /v", Set.of("/p")));
        // case-insensitive flag match
        assertEquals("CONNECT /P [REDACTED:CREDENTIAL]",
                redactor.redactCommandLine("CONNECT /P s3cr3t", Set.of("/p")));
        // a non-sensitive flag is untouched
        assertEquals("ping -n 4 host", redactor.redactCommandLine("ping -n 4 host", Set.of("/p")));
        // a sensitive flag at the very end with no value does not crash and masks nothing
        assertEquals("connect /p", redactor.redactCommandLine("connect /p", Set.of("/p")));
        // empty / null sensitive set -> unchanged
        assertEquals("connect host /p s3cr3t", redactor.redactCommandLine("connect host /p s3cr3t", Set.of()));
        assertEquals("", redactor.redactCommandLine(null, Set.of("/p")));
    }
}
