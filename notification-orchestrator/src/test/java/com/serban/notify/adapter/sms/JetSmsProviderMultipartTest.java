package com.serban.notify.adapter.sms;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JetSMS multipart concatenated SMS feature flag test (Faz 23.3.2 — Codex
 * thread {@code 019e4514} REVISE absorb).
 *
 * <p><b>PR-A1 scope</b>: infrastructure scaffold — feature flag default OFF,
 * JetSMS-specific Latin-5 segment estimator, dynamic {@code maxMessageLength()},
 * configurable {@code onlengthproblem} SOAP envelope param. Runtime davranış
 * default flag ile değişmiyor (legacy 160 hard limit); flag açıkken estimator
 * + max-segments guard + provider-confirmed split (operator-overlay).
 *
 * <p><b>Out of scope (deferred)</b>:
 * <ul>
 *   <li>Provider canary kanıtı (PR-A2)</li>
 *   <li>Audit event metadata + SmsSendResult segment fields (PR-A1.1)</li>
 *   <li>NotificationDelivery.segment_count DB column (PR-B)</li>
 *   <li>DLR aggregate semantik revisit (PR-A2/PR-B)</li>
 * </ul>
 *
 * <p>Test'ler SOAP transport üzerinden koşar (default transport); HTTP
 * transport multipart davranışı paralel (transport=http set sonrası aynı
 * estimator + guard).
 */
class JetSmsProviderMultipartTest {

    @RegisterExtension
    static WireMockExtension jetsms = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private JetSmsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JetSmsProvider();
        // SOAP transport — Faz 23.4 cutover default.
        ReflectionTestUtils.setField(provider, "transport", "soap");
        ReflectionTestUtils.setField(provider, "apiUrl", jetsms.url("/SMS-Web/HttpSmsSend"));
        ReflectionTestUtils.setField(provider, "reportUrl", jetsms.url("/SMS-Web/HttpSmsReport"));
        ReflectionTestUtils.setField(provider, "soapUrl", jetsms.url("/ws/soapSMS.asmx"));
        ReflectionTestUtils.setField(provider, "username", "test-user");
        ReflectionTestUtils.setField(provider, "password", "test-pass");
        ReflectionTestUtils.setField(provider, "originator", "Notify");
        // Feature flag defaults (Spring @Value would normally inject these in
        // production context; unit test must set explicitly).
        ReflectionTestUtils.setField(provider, "multipartEnabled", false);
        ReflectionTestUtils.setField(provider, "maxSegments", 6);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "RejectAllPackage");
    }

    /* ─── Segment estimator (JetSMS-specific Latin-5 baz) ──────────────── */

    @Test
    void estimateLatin5SegmentsEmptyReturnsZero() {
        assertThat(JetSmsProvider.estimateLatin5Segments(null)).isZero();
        assertThat(JetSmsProvider.estimateLatin5Segments("")).isZero();
    }

    @Test
    void estimateLatin5SegmentsSingleAtBoundary() {
        // 160 char = exactly 1 segment (ISO-8859-9 single-segment limit)
        String text = "a".repeat(160);
        assertThat(JetSmsProvider.estimateLatin5Segments(text)).isEqualTo(1);
    }

    @Test
    void estimateLatin5SegmentsJustOverSingleNeedsTwoConcatenated() {
        // 161 char = 2 concatenated segments (UDH overhead → 153 per segment)
        String text = "a".repeat(161);
        assertThat(JetSmsProvider.estimateLatin5Segments(text)).isEqualTo(2);
    }

    @Test
    void estimateLatin5SegmentsExactBoundaryThreeSegments() {
        // 306 char = exactly 2 concatenated segments (2 × 153)
        String text = "a".repeat(306);
        assertThat(JetSmsProvider.estimateLatin5Segments(text)).isEqualTo(2);
    }

    @Test
    void estimateLatin5SegmentsLongMessageMatchesCeilDivision() {
        // 459 char = 3 concatenated segments (ceil(459 / 153) = 3)
        assertThat(JetSmsProvider.estimateLatin5Segments("x".repeat(459)))
            .isEqualTo(3);
        // 460 char crosses into 4th segment
        assertThat(JetSmsProvider.estimateLatin5Segments("x".repeat(460)))
            .isEqualTo(4);
    }

    @Test
    void estimateLatin5SegmentsTurkishCharsCountAsSingleSeptet() {
        // Codex 019e4514 P3 absorb: JetSMS is ISO-8859-9 (Latin-5), Türkçe
        // karakterler (ç ğ ı İ ö ş ü) tek septet sayar — GSM-7 değil. Aynı
        // 160 char Türkçe baz tek segment olarak değerlendirilmeli.
        String turkishText = "Şifre sıfırlama: ğöüçİı".repeat(7);
        // ≈ 23 char × 7 = 161; just barely over single-segment boundary
        assertThat(turkishText.length()).isGreaterThan(160);
        assertThat(JetSmsProvider.estimateLatin5Segments(turkishText))
            .as("Türkçe karakter UCS-2 sayılmaz (JetSMS ISO-8859-9 destekler); "
                + "SmsSegmentEncoder GSM-7+UCS-2 mantığı JetSMS için yanlış")
            .isEqualTo(2);
    }

    /* ─── Dynamic maxMessageLength() (operational multipart guard) ─────── */

    @Test
    void maxMessageLengthDefaultIs160WhenMultipartDisabled() {
        assertThat(provider.maxMessageLength()).isEqualTo(160);
    }

    @Test
    void maxMessageLengthStaysAt160WhenFlagOnButOnLengthProblemStillDefault() {
        // Codex 019e4514 iter-2 P1 absorb: operational multipart koşulu
        // multipartEnabled + onLengthProblem != RejectAllPackage. Flag tek
        // başına yetmez — provider-confirmed split override gerekmez ise
        // legacy 160 hard limit korunur (fail-closed).
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        // onLengthProblem hâlâ default RejectAllPackage (setUp'tan)
        assertThat(provider.maxMessageLength())
            .as("flag açık + default RejectAllPackage = fail-closed 160 limit; "
                + "operator split override set etmediği sürece capability artmaz")
            .isEqualTo(160);
    }

    @Test
    void maxMessageLengthScalesWhenFlagOnAndOnLengthProblemOverridden() {
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        ReflectionTestUtils.setField(provider, "maxSegments", 6);
        // 6 × 153 = 918 char (ISO-8859-9 concatenated worst-case)
        assertThat(provider.maxMessageLength()).isEqualTo(918);
    }

    @Test
    void maxMessageLengthHonoursOperatorMaxSegmentsOverride() {
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        ReflectionTestUtils.setField(provider, "maxSegments", 3);
        assertThat(provider.maxMessageLength()).isEqualTo(3 * 153);  // 459
    }

    @Test
    void isOperationalMultipartGuardMatrix() {
        // Codex 019e4514 iter-2 P1 fail-closed truth table:
        // flag false → false; flag true + default RejectAllPackage → false;
        // flag true + SplitMessage → true; null/blank onLengthProblem → false
        ReflectionTestUtils.setField(provider, "multipartEnabled", false);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        assertThat(provider.isOperationalMultipart()).isFalse();

        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "RejectAllPackage");
        assertThat(provider.isOperationalMultipart()).isFalse();

        ReflectionTestUtils.setField(provider, "onLengthProblem", "rejectallpackage");
        assertThat(provider.isOperationalMultipart())
            .as("case-insensitive default match")
            .isFalse();

        ReflectionTestUtils.setField(provider, "onLengthProblem", null);
        assertThat(provider.isOperationalMultipart()).isFalse();

        ReflectionTestUtils.setField(provider, "onLengthProblem", "  ");
        assertThat(provider.isOperationalMultipart())
            .as("blank trim sonrası default eşleşmesi yok ama trim sonucu boş → false")
            .isFalse();

        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        assertThat(provider.isOperationalMultipart()).isTrue();
    }

    /* ─── send() guard behavior (default flag OFF) ─────────────────────── */

    @Test
    void multipartDisabledRejects161CharAsMessageTooLong() {
        String text = "a".repeat(161);

        SmsSendResult result = provider.send("+905321234567", text);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(SmsFailureClass.MESSAGE_TOO_LONG);
        assertThat(result.providerCode()).isEqualTo("len161");
        // Outbound SOAP never reached — preflight rejected
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void multipartDisabledStillAccepts160CharSingleSegment() {
        // Backward-compat invariant: tek-segment mesaj kabul edilmeli
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "756464245"))));
        String text = "a".repeat(160);

        SmsSendResult result = provider.send("+905321234567", text);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(result.providerMsgId()).isEqualTo("jetsms-756464245");
    }

    /* ─── send() guard behavior (flag ON — multipart enabled) ──────────── */

    @Test
    void multipartEnabledButDefaultOnLengthProblemStillRejects161() {
        // Codex 019e4514 iter-2 P1 absorb: feature flag tek başına yetmez.
        // multipartEnabled=true ama onLengthProblem hâlâ RejectAllPackage
        // ise legacy 160-char hard limit korunur — provider'a uzun mesaj
        // RejectAllPackage ile gönderilirse Status=80 INVALID_TEXT döner ve
        // MESSAGE_TOO_LONG failover taxonomy bypass'lanır. Fail-closed.
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        // onLengthProblem = "RejectAllPackage" (default from setUp)
        String text = "a".repeat(161);

        SmsSendResult result = provider.send("+905321234567", text);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(SmsFailureClass.MESSAGE_TOO_LONG);
        assertThat(result.providerCode()).isEqualTo("len161");
        // Outbound SOAP never reached — operational guard fail-closed
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void multipartOperationalAccepts161CharWithTwoSegmentEstimate() {
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "756464246"))));
        String text = "a".repeat(161);

        SmsSendResult result = provider.send("+905321234567", text);

        assertThat(result.status())
            .as("operational multipart (flag + SplitMessage override) + 161 char "
                + "(estimate=2 segments, ≤ maxSegments=6) ACCEPTED")
            .isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(result.providerMsgId()).isEqualTo("jetsms-756464246");
        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void multipartOperationalSendsOverriddenOnLengthProblemInOutboundEnvelope() {
        // Codex 019e4514 iter-2 P2 absorb: outbound SOAP envelope'da
        // configured onLengthProblem değeri görünmeli (static helper test
        // yetmez; sendViaSoap() doğru overload'u çağırdığını kanıtla).
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "756464260"))));
        String text = "a".repeat(200);  // 2 segment

        provider.send("+905321234567", text);

        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<onlengthproblem>SplitMessage</onlengthproblem>")));
    }

    @Test
    void multipartOperationalAccepts459CharThreeSegment() {
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "756464247"))));
        // 459 char = 3 concatenated segments (3×153)
        String text = "a".repeat(459);

        SmsSendResult result = provider.send("+905321234567", text);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(JetSmsProvider.estimateLatin5Segments(text)).isEqualTo(3);
    }

    @Test
    void multipartOperationalRejectsBeyondMaxSegments() {
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        ReflectionTestUtils.setField(provider, "maxSegments", 6);
        // 6 × 153 + 1 = 919 char crosses into 7th segment
        String text = "a".repeat(919);
        assertThat(JetSmsProvider.estimateLatin5Segments(text)).isEqualTo(7);

        SmsSendResult result = provider.send("+905321234567", text);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(SmsFailureClass.MESSAGE_TOO_LONG);
        assertThat(result.providerCode())
            .as("provider code segment count'u taşır (billing diagnostics)")
            .isEqualTo("segments7");
        // Outbound SOAP never reached — caller-side guard
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void multipartOperationalStillRejectsEmojiViaCharsetPreflight() {
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        // Emoji ISO-8859-9 encode edilemez — operational multipart bile
        // bypass yapmaz; UNSUPPORTED_CHARSET ile reddedilir → SmsAdapter
        // Unicode destekleyen secondary'ye (NetGSM) route eder.
        String text = "Hello 🎉 emoji";

        SmsSendResult result = provider.send("+905321234567", text);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(SmsFailureClass.UNSUPPORTED_CHARSET);
    }

    @Test
    void multipartOperationalHonoursOperatorMaxSegmentsOverrideThree() {
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SplitMessage");
        ReflectionTestUtils.setField(provider, "maxSegments", 3);
        // 3 × 153 + 1 = 460 char crosses into 4th segment
        String text = "a".repeat(460);
        assertThat(JetSmsProvider.estimateLatin5Segments(text)).isEqualTo(4);

        SmsSendResult result = provider.send("+905321234567", text);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(SmsFailureClass.MESSAGE_TOO_LONG);
        assertThat(result.providerCode()).isEqualTo("segments4");
    }

    /* ─── onLengthProblem configurable SOAP envelope ───────────────────── */

    @Test
    void buildSendSoapEnvelopeBackwardCompatDefaultsToRejectAllPackage() {
        // Codex absorb: backward-compat overload — eski signature aynı default
        String envelope = JetSmsProvider.buildSendSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello");
        assertThat(envelope).contains("<onlengthproblem>RejectAllPackage</onlengthproblem>");
    }

    @Test
    void buildSendSoapEnvelopeWithExplicitOnLengthProblemSplitMessage() {
        // Operator overlay'inde provider-confirmed split değeri set ettiğinde
        // SOAP envelope o değeri taşır
        String envelope = JetSmsProvider.buildSendSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello", "SplitMessage");
        assertThat(envelope).contains("<onlengthproblem>SplitMessage</onlengthproblem>");
    }

    @Test
    void buildSendSoapEnvelopeNullOnLengthProblemDefaultsToRejectAllPackage() {
        // Null/blank fallback — fail-safe
        String envelopeNull = JetSmsProvider.buildSendSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello", null);
        String envelopeBlank = JetSmsProvider.buildSendSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello", "  ");

        assertThat(envelopeNull).contains("<onlengthproblem>RejectAllPackage</onlengthproblem>");
        assertThat(envelopeBlank).contains("<onlengthproblem>RejectAllPackage</onlengthproblem>");
    }

    @Test
    void buildSendSoapEnvelopeOnLengthProblemIsXmlEscaped() {
        // Configuration override güvenliği — XML control chars escape
        String envelope = JetSmsProvider.buildSendSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello", "Inject<&>");
        assertThat(envelope)
            .contains("<onlengthproblem>Inject&lt;&amp;&gt;</onlengthproblem>")
            .doesNotContain("<onlengthproblem>Inject<&></onlengthproblem>");
    }

    /* ─── SmsAdapter capability route preserved ─────────────────────────── */

    @Test
    void supportsUnicodeRemainsFalseRegardlessOfMultipartFlag() {
        // JetSMS UCS-2 desteklemediği için flag durumundan bağımsız false.
        // SmsAdapter charset-route mantığı korunur.
        assertThat(provider.supportsUnicode()).isFalse();
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        assertThat(provider.supportsUnicode()).isFalse();
    }

    /* ─── Helpers ───────────────────────────────────────────────────────── */

    private static String soapAcceptedEnvelope(String errorCode, String id) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body>"
            + "<SendSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<SendSMSResult>"
            + "<ErrorCode>" + errorCode + "</ErrorCode>"
            + "<ID>" + id + "</ID>"
            + "</SendSMSResult>"
            + "</SendSMSResponse>"
            + "</soap:Body>"
            + "</soap:Envelope>";
    }
}
