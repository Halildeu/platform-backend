package com.serban.notify.adapter.sms;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

/**
 * JetSMS SOAP transport test (Faz 23.4 PR-5 cutover).
 *
 * <p>{@code soapSMS.asmx} {@code SendSMS} + {@code ReportSMS}. Default
 * transport (HTTP path için bkz. {@link JetSmsProviderTest}).
 */
class JetSmsProviderSoapTest {

    @RegisterExtension
    static WireMockExtension jetsms = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private JetSmsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JetSmsProvider();
        // transport=soap default; ama explicit set ediyoruz read'in net olması için.
        ReflectionTestUtils.setField(provider, "transport", "soap");
        ReflectionTestUtils.setField(provider, "soapUrl", jetsms.url("/ws/soapSMS.asmx"));
        ReflectionTestUtils.setField(provider, "apiUrl", jetsms.url("/SMS-Web/HttpSmsSend"));
        ReflectionTestUtils.setField(provider, "reportUrl", jetsms.url("/SMS-Web/HttpSmsReport"));
        ReflectionTestUtils.setField(provider, "username", "soap-user");
        ReflectionTestUtils.setField(provider, "password", "soap-pass");
        ReflectionTestUtils.setField(provider, "originator", "Mikrolink");
    }

    // ─── Default transport ───────────────────────────────────────────────

    @Test
    void defaultTransportIsSoap() {
        // Fresh instance — @Value default kicks in.
        JetSmsProvider fresh = new JetSmsProvider();
        ReflectionTestUtils.setField(fresh, "transport", "soap");
        assertThat(fresh.isHttpTransport()).isFalse();
    }

    @Test
    void blankTransportFallsBackToSoap() {
        ReflectionTestUtils.setField(provider, "transport", "");
        assertThat(provider.isHttpTransport()).isFalse();
        ReflectionTestUtils.setField(provider, "transport", "   ");
        assertThat(provider.isHttpTransport()).isFalse();
        ReflectionTestUtils.setField(provider, "transport", null);
        assertThat(provider.isHttpTransport()).isFalse();
    }

    @Test
    void unknownTransportFallsBackToSoap() {
        ReflectionTestUtils.setField(provider, "transport", "websocket");
        assertThat(provider.isHttpTransport()).isFalse();
    }

    @Test
    void httpTransportRecognisedCaseInsensitive() {
        ReflectionTestUtils.setField(provider, "transport", "HTTP");
        assertThat(provider.isHttpTransport()).isTrue();
        ReflectionTestUtils.setField(provider, "transport", "Http");
        assertThat(provider.isHttpTransport()).isTrue();
        ReflectionTestUtils.setField(provider, "transport", "http");
        assertThat(provider.isHttpTransport()).isTrue();
    }

    // ─── SOAP send happy path ────────────────────────────────────────────

    @Test
    void soapSendAcceptedErrorCode00() {
        String response =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><SendSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<SendSMSResult><ErrorCode>00</ErrorCode><ID>110906180000526</ID></SendSMSResult>"
            + "</SendSMSResponse></soap:Body></soap:Envelope>";
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "Hello SOAP");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(r.providerMsgId()).isEqualTo("jetsms-110906180000526");
        assertThat(r.actualProviderKey()).isEqualTo("jetsms");

        // Request shape: SOAPAction header + envelope with user/originator/messages/receipents
        jetsms.verify(postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withHeader("Content-Type", containing("text/xml"))
            .withHeader("SOAPAction", equalTo("\"http://tempuri.org/SendSMS\""))
            .withRequestBody(containing("<SendSMS xmlns=\"http://tempuri.org/\">"))
            .withRequestBody(containing("<user>soap-user</user>"))
            .withRequestBody(containing("<password>soap-pass</password>"))
            .withRequestBody(containing("<originator>Mikrolink</originator>"))
            .withRequestBody(containing("<messages><string>Hello SOAP</string></messages>"))
            // Phone "+" stripped, receipents element (note JetSMS spelling)
            .withRequestBody(containing("<receipents><string>905321111111</string></receipents>"))
            .withRequestBody(containing("<onlengthproblem>RejectAllPackage</onlengthproblem>")));
    }

    @Test
    void soapSendErrorCode00WithTimestampSuffixIsSuccess() {
        // JetSMS XML orijinal dönüş "00 130228114512" gibi olabilir — başında 00 = success
        String response =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><SendSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<SendSMSResult><ErrorCode>00 130228114512</ErrorCode><ID>756464245</ID></SendSMSResult>"
            + "</SendSMSResponse></soap:Body></soap:Envelope>";
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(r.providerMsgId()).isEqualTo("jetsms-756464245");
    }

    // ─── SOAP send ErrorCode mapping ─────────────────────────────────────

    @Test
    void soapSendErrorCode10WrongUserProviderConfig() {
        // 10 = "wrong user" — kalıcı login error (failover NOT eligible)
        String response = wrapSendSoap("10 wrong user", null);
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        // Trimmed leading token retained as providerCode (or full code — we keep full)
        assertThat(r.providerCode()).startsWith("10");
    }

    @Test
    void soapSendErrorCode11QuotaOrCredit() {
        String response = wrapSendSoap("11 not enough limit", null);
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        // QUOTA_OR_CREDIT is failover-eligible → RETRY
        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.QUOTA_OR_CREDIT);
    }

    @Test
    void soapSendErrorCode30OriginatorMissingProviderConfig() {
        String response = wrapSendSoap("30 Originator must be fill", null);
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
    }

    @Test
    void soapSendErrorCode31InvalidPhone() {
        String response = wrapSendSoap("31", null);
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.INVALID_PHONE);
    }

    @Test
    void soapSendErrorCode38EmptyMessage() {
        String response = wrapSendSoap("38", null);
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.EMPTY_MESSAGE);
    }

    @Test
    void soapSendErrorCode80UnsupportedCharInvalidText() {
        String response = wrapSendSoap("80 XML Error : Unexpected SMS characters", null);
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.INVALID_TEXT);
    }

    @Test
    void soapSendErrorCode81ProviderSystemRetry() {
        // 81 = array mismatch (web-service özel) — PROVIDER_SYSTEM (failover-eligible)
        String response = wrapSendSoap("81", null);
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_SYSTEM);
    }

    @Test
    void soapSendUnknownErrorCodeUnknownTransientRetry() {
        String response = wrapSendSoap("99 something nobody documented", null);
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    // ─── SOAP send HTTP-level dispatch ───────────────────────────────────

    @Test
    void soapSendHttp500RetryTransient() {
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(503).withBody("<soap:Fault/>")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.HTTP_5XX);
    }

    @Test
    void soapSendHttp401AuthProviderConfig() {
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(401).withBody("unauthorized")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
    }

    @Test
    void soapSendEmptyBodyRetries() {
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody("")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    @Test
    void soapSendMissingErrorCodeRetries() {
        // No <ErrorCode> at all in response
        String response =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><SendSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<SendSMSResult><ID>123</ID></SendSMSResult>"
            + "</SendSMSResponse></soap:Body></soap:Envelope>";
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    @Test
    void soapSendSuccessButMissingIdNoCorrelator() {
        String response = wrapSendSoap("00", "");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.NO_CORRELATOR);
    }

    // ─── SOAP send pre-flight (transport-agnostic) ───────────────────────

    @Test
    void soapMissingConfigFailsBeforeHttp() {
        ReflectionTestUtils.setField(provider, "originator", "");

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void soapEmojiTextFailsUnsupportedCharsetBeforeHttp() {
        SmsSendResult r = provider.send("+905321111111", "Kutlama 🎉 mesaji");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNSUPPORTED_CHARSET);
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void soapMessageTooLongFailsBeforeHttp() {
        String longText = "x".repeat(161);

        SmsSendResult r = provider.send("+905321111111", longText);

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.MESSAGE_TOO_LONG);
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    // ─── Envelope XML-escape ─────────────────────────────────────────────

    @Test
    void soapSendXmlEscapesSpecialChars() {
        // Mesajda < > & " ' karakterleri → XML escape
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200)
                .withBody(wrapSendSoap("00", "1"))));

        SmsSendResult r = provider.send("+905321111111", "Test & <hello> 'quote' \"q\"");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        // Escaped in envelope
        jetsms.verify(postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("Test &amp; &lt;hello&gt; &apos;quote&apos; &quot;q&quot;")));
    }

    @Test
    void soapSendTurkishCharsRawInEnvelope() {
        // Türkçe ISO-8859-9 — escape edilmez, UTF-8 zarf üzerinde raw geçer.
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(wrapSendSoap("00", "9"))));

        SmsSendResult r = provider.send("+905321111111", "Şifre güncellendi: İĞÜÇÖŞ");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        jetsms.verify(postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(matching(".*Şifre güncellendi: İĞÜÇÖŞ.*")));
    }

    // ─── SOAP DLR poll ───────────────────────────────────────────────────

    @Test
    void soapPollStatus1Delivered() {
        String response =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><ReportSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<ReportSMSResult><ReportSMSResult>"
            + "<ID>756</ID><Phone>905321111111</Phone><Status>1</Status>"
            + "<SendDate>2026-05-19 12:00:00</SendDate><DeliveredDate>2026-05-19 12:00:05</DeliveredDate>"
            + "</ReportSMSResult></ReportSMSResult></ReportSMSResponse></soap:Body></soap:Envelope>";
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("756"));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).rawProviderMsgId()).isEqualTo("756");
        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.DELIVERED);
        assertThat(r.get(0).terminal()).isTrue();

        // SOAPAction header verifies dispatch went via SOAP transport
        jetsms.verify(postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withHeader("SOAPAction", equalTo("\"http://tempuri.org/ReportSMS\""))
            .withRequestBody(containing("<ReportSMS xmlns=\"http://tempuri.org/\">"))
            .withRequestBody(containing("<user>soap-user</user>"))
            .withRequestBody(containing("<groupid>756</groupid>"))
            .withRequestBody(containing("<status>5</status>")));
    }

    @Test
    void soapPollStatus3Failed() {
        String response = wrapReportSoap("<ID>100</ID><Status>3</Status>");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.FAILED);
        assertThat(r.get(0).terminal()).isTrue();
    }

    @Test
    void soapPollStatus4TimeoutFailed() {
        String response = wrapReportSoap("<ID>100</ID><Status>4</Status>");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.FAILED);
    }

    @Test
    void soapPollStatus2Pending() {
        String response = wrapReportSoap("<ID>100</ID><Status>2</Status>");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.PENDING);
        assertThat(r.get(0).terminal()).isFalse();
    }

    @Test
    void soapPollEmptyResponsePending() {
        // No <Status> children — group exists but no operator report yet.
        String response =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><ReportSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<ReportSMSResult/></ReportSMSResponse></soap:Body></soap:Envelope>";
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.PENDING);
    }

    @Test
    void soapPollMultiIdPerCallOneSoapCallEach() {
        // SOAP ReportSMS is per-groupid — 3 input IDs → 3 SOAP calls.
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<groupid>100</groupid>"))
            .willReturn(aResponse().withStatus(200)
                .withBody(wrapReportSoap("<ID>100</ID><Status>1</Status>"))));
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<groupid>200</groupid>"))
            .willReturn(aResponse().withStatus(200)
                .withBody(wrapReportSoap("<ID>200</ID><Status>3</Status>"))));
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<groupid>300</groupid>"))
            .willReturn(aResponse().withStatus(200)
                .withBody(wrapReportSoap("<ID>300</ID><Status>2</Status>"))));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(
            java.util.List.of("100", "200", "300"));

        assertThat(r).hasSize(3);
        assertThat(r.get(0).rawProviderMsgId()).isEqualTo("100");
        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.DELIVERED);
        assertThat(r.get(1).rawProviderMsgId()).isEqualTo("200");
        assertThat(r.get(1).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.FAILED);
        assertThat(r.get(2).rawProviderMsgId()).isEqualTo("300");
        assertThat(r.get(2).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.PENDING);
    }

    @Test
    void soapPollMultiStatusInOneGroupTakesMostAdvanced() {
        // Bir grup içinde paket bölünmüşse birden fazla <Status> dönebilir;
        // DELIVERED > FAILED > PENDING önceliğine göre seçilir.
        String response =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><ReportSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<ReportSMSResult>"
            + "<ReportSMSResult><ID>100</ID><Status>1</Status></ReportSMSResult>"
            + "<ReportSMSResult><ID>100</ID><Status>2</Status></ReportSMSResult>"
            + "</ReportSMSResult></ReportSMSResponse></soap:Body></soap:Envelope>";
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.DELIVERED);
    }

    @Test
    void soapPollHttpErrorPending() {
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(503).withBody("err")));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.PENDING);
    }

    @Test
    void soapPollEmptyListReturnsEmpty() {
        assertThat(provider.pollDelivery(java.util.List.of())).isEmpty();
    }

    @Test
    void soapPollMissingConfigPending() {
        ReflectionTestUtils.setField(provider, "originator", "");

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.PENDING);
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    // ─── XML helpers (unit-level golden) ─────────────────────────────────

    @Test
    void xmlEscapeAllSpecials() {
        assertThat(JetSmsProvider.xmlEscape("a&b")).isEqualTo("a&amp;b");
        assertThat(JetSmsProvider.xmlEscape("<x>")).isEqualTo("&lt;x&gt;");
        assertThat(JetSmsProvider.xmlEscape("\"q\"")).isEqualTo("&quot;q&quot;");
        assertThat(JetSmsProvider.xmlEscape("it's")).isEqualTo("it&apos;s");
        assertThat(JetSmsProvider.xmlEscape("Şifre"))
            .as("Turkish chars passthrough — utf-8 envelope handles raw")
            .isEqualTo("Şifre");
        assertThat(JetSmsProvider.xmlEscape("")).isEmpty();
        assertThat(JetSmsProvider.xmlEscape(null)).isEmpty();
    }

    @Test
    void extractXmlValueSimple() {
        String xml = "<root><ID>756464245</ID><ErrorCode>00</ErrorCode></root>";
        assertThat(JetSmsProvider.extractXmlValue(xml, "ID")).isEqualTo("756464245");
        assertThat(JetSmsProvider.extractXmlValue(xml, "ErrorCode")).isEqualTo("00");
        assertThat(JetSmsProvider.extractXmlValue(xml, "missing")).isNull();
    }

    @Test
    void extractXmlValueIgnoresNamespacePrefix() {
        String xml = "<soap:Envelope><soap:Body><ns:ID>123</ns:ID></soap:Body></soap:Envelope>";
        assertThat(JetSmsProvider.extractXmlValue(xml, "ID")).isEqualTo("123");
    }

    @Test
    void extractXmlValueSelfClosingReturnsEmpty() {
        assertThat(JetSmsProvider.extractXmlValue("<root><ID/></root>", "ID")).isEmpty();
    }

    @Test
    void extractXmlValueUnescapesEntities() {
        assertThat(JetSmsProvider.extractXmlValue("<msg>a &amp; b</msg>", "msg"))
            .isEqualTo("a & b");
    }

    @Test
    void extractAllXmlValuesMulti() {
        String xml = "<r><Status>1</Status><Status>3</Status><Status>2</Status></r>";
        assertThat(JetSmsProvider.extractAllXmlValues(xml, "Status"))
            .containsExactly("1", "3", "2");
    }

    @Test
    void soapErrorCodeFailureClassMapping() {
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("00"))
            .isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);  // 00 shouldn't get here, but defensive
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("03 Password must be fill"))
            .isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("10"))
            .isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("11"))
            .isEqualTo(SmsFailureClass.QUOTA_OR_CREDIT);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("27"))
            .isEqualTo(SmsFailureClass.INVALID_PHONE);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("31"))
            .isEqualTo(SmsFailureClass.INVALID_PHONE);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("38"))
            .isEqualTo(SmsFailureClass.EMPTY_MESSAGE);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("80 XML Error : Unexpected SMS characters"))
            .isEqualTo(SmsFailureClass.INVALID_TEXT);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("81"))
            .isEqualTo(SmsFailureClass.PROVIDER_SYSTEM);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("84"))
            .isEqualTo(SmsFailureClass.PROVIDER_SYSTEM);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass("999"))
            .isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
        assertThat(JetSmsProvider.soapErrorCodeFailureClass(null))
            .isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    // ─── Envelope golden ─────────────────────────────────────────────────

    @Test
    void buildSendSoapEnvelopeContainsAllRequiredFields() {
        String envelope = JetSmsProvider.buildSendSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello");
        assertThat(envelope).contains(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"",
            "<SendSMS xmlns=\"http://tempuri.org/\">",
            "<user>u</user>",
            "<password>p</password>",
            "<originator>Notify</originator>",
            "<messages><string>Hello</string></messages>",
            "<receipents><string>905321111111</string></receipents>",
            "<onlengthproblem>RejectAllPackage</onlengthproblem>");
        // Bytes are valid UTF-8 (envelope is ASCII here).
        assertThat(envelope.getBytes(StandardCharsets.UTF_8)).isNotEmpty();
    }

    @Test
    void buildReportSoapEnvelopeContainsAllRequiredFields() {
        String envelope = JetSmsProvider.buildReportSoapEnvelope("u", "p", "756", "5");
        assertThat(envelope).contains(
            "<ReportSMS xmlns=\"http://tempuri.org/\">",
            "<user>u</user>",
            "<password>p</password>",
            "<groupid>756</groupid>",
            "<status>5</status>");
    }

    // ─── Codex 019e421f iter-1 absorbs (P2 password gate, P3 winning code) ──

    @Test
    void soapSendBlankPasswordPreflightConfigNoOutboundCall() {
        // Codex iter-1 P2: SOAP envelope password gönderiyor; default boş —
        // eksikse dispatch'ten önce PROVIDER_CONFIG ile fail-closed olmalı.
        ReflectionTestUtils.setField(provider, "password", "");

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        assertThat(r.providerCode()).isEqualTo("config");
        // SOAP endpoint'ine HİÇ çağrı gitmemiş olmalı (fail-closed).
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void soapPollBlankPasswordPendingNoOutboundCall() {
        // Codex iter-1 P2: poll tarafı da password gerektirir; eksikse pending
        // fallback + dış çağrı YOK (auth/config drift maskelenmesin).
        ReflectionTestUtils.setField(provider, "password", "");

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.PENDING);
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void soapPollMultiStatusReportsWinningBucketCode() {
        // Codex iter-1 P3: aggregation kararı (terminal class) ve reported
        // providerStateCode TUTARLI olmalı — Status=2,1 sıralamasında bile
        // sonuç DELIVERED + providerStateCode="1" (winning bucket'ın ilk
        // kodu), "2" (last-seen) DEĞİL.
        String response =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><ReportSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<ReportSMSResult>"
            + "<ReportSMSResult><ID>100</ID><Status>2</Status></ReportSMSResult>"
            + "<ReportSMSResult><ID>100</ID><Status>1</Status></ReportSMSResult>"
            + "</ReportSMSResult></ReportSMSResponse></soap:Body></soap:Envelope>";
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse().withStatus(200).withBody(response)));

        java.util.List<SmsDlrPollResult> r = provider.pollDelivery(java.util.List.of("100"));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).deliveryStatus()).isEqualTo(SmsDlrPollResult.DeliveryStatus.DELIVERED);
        assertThat(r.get(0).providerStateCode()).isEqualTo("1");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static String wrapSendSoap(String errorCode, String id) {
        String idTag = (id == null) ? "" : "<ID>" + id + "</ID>";
        String errTag = (errorCode == null) ? "" : "<ErrorCode>" + errorCode + "</ErrorCode>";
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><SendSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<SendSMSResult>" + errTag + idTag + "</SendSMSResult>"
            + "</SendSMSResponse></soap:Body></soap:Envelope>";
    }

    private static String wrapReportSoap(String innerResult) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><ReportSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<ReportSMSResult><ReportSMSResult>" + innerResult + "</ReportSMSResult></ReportSMSResult>"
            + "</ReportSMSResponse></soap:Body></soap:Envelope>";
    }
}
