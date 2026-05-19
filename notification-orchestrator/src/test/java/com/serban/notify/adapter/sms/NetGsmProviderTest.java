package com.serban.notify.adapter.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NetGSM SMS provider test (Faz 23.3 multi-provider — PR-1 abstraction).
 *
 * <p>Eski {@code NetGsmSmsAdapterTest} refactor'u: artık {@link NetGsmProvider}
 * ({@code SmsProvider}) doğrudan test edilir — {@code send(phone, text)} →
 * {@link SmsSendResult}. Channel-level validation (E.164, empty message)
 * {@code SmsAdapterTest}'e taşındı.
 *
 * <p>Behavior-neutral kontrol: NetGSM HTTP status semantiği eski adapter ile
 * birebir aynı (ACCEPTED on code=00+jobid, RETRY on 5xx/transient, FAILED on
 * permanent code) — yalnızca {@link SmsFailureClass} taxonomy eklendi.
 */
class NetGsmProviderTest {

    @RegisterExtension
    static WireMockExtension netgsm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NetGsmProvider provider;

    @BeforeEach
    void setUp() {
        provider = new NetGsmProvider(objectMapper);
        ReflectionTestUtils.setField(provider, "apiUrl", netgsm.url("/sms/rest/v2/send"));
        ReflectionTestUtils.setField(provider, "username", "test-user");
        ReflectionTestUtils.setField(provider, "password", "test-pass");
        ReflectionTestUtils.setField(provider, "msgheader", "Notify");
    }

    // ─── Provider metadata ───────────────────────────────────────────────

    @Test
    void providerKeyIsNetgsm() {
        assertThat(provider.providerKey()).isEqualTo("netgsm");
    }

    @Test
    void dlrModeIsPush() {
        assertThat(provider.dlrMode()).isEqualTo(SmsProvider.SmsDlrMode.PUSH);
    }

    @Test
    void supportsUnicodeTrue() {
        // NetGSM UCS-2 (encoding=TR)
        assertThat(provider.supportsUnicode()).isTrue();
    }

    @Test
    void pollDeliveryUnsupportedForPushProvider() {
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> provider.pollDelivery(java.util.List.of("x")));
    }

    // ─── Happy path ──────────────────────────────────────────────────────

    @Test
    void accepted2xxWithCode00AndJobid() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"00\",\"description\":\"OK\",\"jobid\":\"abc-123\"}")));

        SmsSendResult r = provider.send("+905321111111", "Hello SMS");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(r.providerMsgId()).isEqualTo("netgsm-abc-123");  // DLR correlator
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.NONE);

        // Phone "+" stripped, BasicAuth header set, GSM-7 encoding ""
        netgsm.verify(postRequestedFor(urlEqualTo("/sms/rest/v2/send"))
            .withHeader("Content-Type", containing("application/json"))
            .withHeader("Authorization", containing("Basic "))
            .withRequestBody(matchingJsonPath("$.msgheader", containing("Notify")))
            .withRequestBody(matchingJsonPath("$.messages[0].no", containing("905321111111")))
            .withRequestBody(matchingJsonPath("$.messages[0].msg", containing("Hello SMS"))));
    }

    @Test
    void code00MissingJobidRetriesNoCorrelator() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"00\",\"description\":\"OK\"}")));

        SmsSendResult r = provider.send("+905322222222", "body");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.NO_CORRELATOR);
    }

    @Test
    void http200MissingProviderCodeRetries() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"description\":\"unknown response shape\"}")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    @Test
    void http200EmptyBodyRetries() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200).withBody("")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    @Test
    void http200MalformedJsonRetries() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200).withBody("not-a-json{")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    @Test
    void http500EmptyBodyRetries() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(502).withBody("")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.HTTP_5XX);
    }

    @Test
    void acceptedTurkishTextSetsEncodingTr() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"00\",\"jobid\":\"tr-1\"}")));

        SmsSendResult r = provider.send("+905323333333", "Şifre güncellendi");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        netgsm.verify(postRequestedFor(urlEqualTo("/sms/rest/v2/send"))
            .withRequestBody(matchingJsonPath("$.encoding", containing("TR"))));
    }

    // ─── HTTP-level dispatch ─────────────────────────────────────────────

    @Test
    void http500RetryTransient() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(503).withBody("service unavailable")));

        SmsSendResult r = provider.send("+905324444444", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.HTTP_5XX);
    }

    @Test
    void http400PermanentFailed() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(400).withBody("bad request")));

        SmsSendResult r = provider.send("+905325555555", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_SYSTEM);
    }

    @Test
    void http401AuthFailedProviderConfig() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(401).withBody("unauthorized")));

        SmsSendResult r = provider.send("+905325555555", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
    }

    // ─── Provider code dispatch ──────────────────────────────────────────

    @Test
    void providerCode20MessageTooLong() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"20\",\"description\":\"too long\"}")));

        SmsSendResult r = provider.send("+905326666666", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.MESSAGE_TOO_LONG);
    }

    @Test
    void providerCode50InvalidPhonePermanentFailed() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"50\",\"description\":\"invalid phone format\"}")));

        SmsSendResult r = provider.send("+905326666666", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.INVALID_PHONE);
        assertThat(r.providerCode()).isEqualTo("50");
    }

    @Test
    void providerCode70IysOptOutPermanentFailed() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"70\",\"description\":\"IYS opt-out\"}")));

        SmsSendResult r = provider.send("+905327777777", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.IYS_OPT_OUT);
    }

    @Test
    void providerCode60InsufficientCreditRetry() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"60\",\"description\":\"insufficient credit\"}")));

        SmsSendResult r = provider.send("+905328888888", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.QUOTA_OR_CREDIT);
    }

    @Test
    void providerUnknownCodeRetryConservative() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"99\",\"description\":\"unknown\"}")));

        SmsSendResult r = provider.send("+905329999999", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    @Test
    void missingCredentialsFailsBeforeSendProviderConfig() {
        ReflectionTestUtils.setField(provider, "username", "");

        SmsSendResult r = provider.send("+905321234567", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
    }

    @Test
    void emptyTextFailsEmptyMessage() {
        SmsSendResult r = provider.send("+905321234567", "  ");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.EMPTY_MESSAGE);
    }

    // ─── Permanent class helper ──────────────────────────────────────────

    @Test
    void permanentClassClassification() {
        assertThat(NetGsmProvider.permanentClass("20")).isEqualTo(SmsFailureClass.MESSAGE_TOO_LONG);
        assertThat(NetGsmProvider.permanentClass("30")).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        assertThat(NetGsmProvider.permanentClass("40")).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        assertThat(NetGsmProvider.permanentClass("50")).isEqualTo(SmsFailureClass.INVALID_PHONE);
        assertThat(NetGsmProvider.permanentClass("70")).isEqualTo(SmsFailureClass.IYS_OPT_OUT);
        assertThat(NetGsmProvider.permanentClass("60")).isNull();  // credit retry
        assertThat(NetGsmProvider.permanentClass("00")).isNull();
        assertThat(NetGsmProvider.permanentClass("99")).isNull();  // unknown retry
        assertThat(NetGsmProvider.permanentClass(null)).isNull();
    }
}
