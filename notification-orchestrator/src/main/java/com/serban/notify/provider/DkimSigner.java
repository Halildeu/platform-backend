package com.serban.notify.provider;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Security;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * DkimSigner — app-side DKIM signer (Faz 23.2 PR-A — Codex 019dfae5 Q1 PARTIAL absorb).
 *
 * <p>Codex Q1 absorb:
 * <ul>
 *   <li>23.2 default mode: app-side DKIM (Plan A) — selector + private_pem
 *       Vault'tan inject; corporate relay DKIM yoksa fail-closed</li>
 *   <li>Production fail-closed: prod profile DKIM absent ise startup fail
 *       ({@link ProductionConfigValidator})</li>
 *   <li>Plan B (corporate relay DKIM) opsiyonel mode: ops alignment + 90-day
 *       rotation SLA gerektirir — flag-gated</li>
 * </ul>
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code notify.dkim.enabled} (default false; prod: true)</li>
 *   <li>{@code notify.dkim.selector} — DKIM selector (DNS TXT record name)</li>
 *   <li>{@code notify.dkim.domain} — signing domain (Header From: domain)</li>
 *   <li>{@code notify.dkim.private-key-pem} — RSA private key (Vault inject)</li>
 * </ul>
 *
 * <p>Signature header format (RFC 6376):
 * <pre>
 * DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed;
 *   d=&lt;domain&gt;; s=&lt;selector&gt;; t=&lt;timestamp&gt;;
 *   bh=&lt;body-hash&gt;;
 *   h=From:To:Subject:Date:Message-ID;
 *   b=&lt;signature-base64&gt;
 * </pre>
 *
 * <p>NOTE: Bu PR-A foundation — actual DKIM library integration (Spring DkimSigner
 * veya bouncycastle-direct) iter-2'de tamamlanacak. Bu sınıf interface +
 * config + fail-closed gate sağlar.
 */
@Component
@ConditionalOnProperty(name = "notify.dkim.enabled", havingValue = "true")
public class DkimSigner {

    private static final Logger log = LoggerFactory.getLogger(DkimSigner.class);

    static {
        // Codex Q1 absorb: BouncyCastle for PEM parsing + RSA-SHA256
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final String selector;
    private final String domain;
    private final PrivateKey privateKey;

    public DkimSigner(
        @Value("${notify.dkim.selector}") String selector,
        @Value("${notify.dkim.domain}") String domain,
        @Value("${notify.dkim.private-key-pem}") String privateKeyPem
    ) {
        this.selector = selector;
        this.domain = domain;
        this.privateKey = parsePrivateKey(privateKeyPem);
        log.info("DkimSigner activated: selector={} domain={}", selector, domain);
    }

    /**
     * Sign a MIME message with DKIM-Signature header.
     *
     * <p>NOTE: Foundation stub — full DKIM library integration follow-up.
     * Şu anki implementation header placeholder ekler; full RFC 6376
     * compliance Faz 23.2 iter-2 PR-A.b'de tamamlanır.
     *
     * @param message MIME message to sign (header DKIM-Signature added in-place)
     * @throws MessagingException on header write failure
     */
    public void sign(MimeMessage message) throws MessagingException {
        // Foundation: header reservation; full sign body-hash + canonicalization
        // RFC 6376 compliance follow-up commit
        String header = String.format(
            "v=1; a=rsa-sha256; c=relaxed/relaxed; d=%s; s=%s; t=%d; "
                + "bh=PLACEHOLDER; h=From:To:Subject:Date:Message-ID; b=PLACEHOLDER",
            domain, selector, System.currentTimeMillis() / 1000
        );
        message.setHeader("DKIM-Signature", header);
        log.debug("DKIM-Signature header reserved (foundation stub): selector={} domain={}",
            selector, domain);
    }

    public boolean isReady() {
        return privateKey != null && selector != null && !selector.isBlank()
            && domain != null && !domain.isBlank();
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String pemContent = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(pemContent);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalStateException("DKIM private key parse failed", e);
        }
    }
}
