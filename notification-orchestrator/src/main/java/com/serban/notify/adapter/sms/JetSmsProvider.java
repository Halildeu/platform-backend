package com.serban.notify.adapter.sms;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * JetSMS SMS provider — Faz 23.3 multi-provider (Codex `019e3f82` AGREE, PR-2)
 * + Faz 23.4 SOAP transport (cutover).
 *
 * <h3>Transport — SOAP (default) | HTTP (additive)</h3>
 *
 * <p>JetSMS iki ayrı API yüzeyi sunar; bu provider <b>her ikisini de</b>
 * destekler ve {@code notify.adapters.sms.jetsms.transport} property
 * ({@code soap} | {@code http}) ile seçilir:
 *
 * <ul>
 *   <li><b>SOAP</b> ({@code soapSMS.asmx}) — <b>default</b>. {@code mikrolink2}
 *       hesabı SOAP servisi için provisioned (canlı {@code SendSMS} testi
 *       gerçek SMS + DLR Status 1 döndü).</li>
 *   <li><b>HTTP</b> ({@code SMS-Web/HttpSmsSend}) — additive olarak korunur.
 *       Aynı hesap HTTP API'de {@code Status=-5} (login error) veriyor
 *       (hesap HTTP için provisioned değil); Biotekno provisioning sonrası
 *       {@code transport=http} ile aktive edilebilir.</li>
 * </ul>
 *
 * <p>{@link #send} / {@link #pollDelivery} ortak validasyonu (config gate,
 * empty/length guard, charset preflight) yapar, sonra seçili transport'a
 * dispatch eder ({@link #sendViaSoap}/{@link #sendViaHttp},
 * {@link #pollViaSoap}/{@link #pollViaHttp}).
 *
 * <h3>Encoding — ISO-8859-9 (Türkçe Latin-5)</h3>
 *
 * <p>HTTP transport: body bytes {@code ISO-8859-9} ile encode edilir,
 * standart {@code URLEncoder} (UTF-8) <b>kullanılmaz</b>. Yalnızca
 * {@code !*'();:@&=+$,/?%#[]} reserved karakterleri {@code %XX} hex'e
 * çevrilir; Türkçe karakterler raw bırakılır (byte-level ISO-8859-9).
 * SOAP transport: zarf UTF-8 gönderilir, alanlar XML-escape edilir.
 *
 * <p><b>Charset preflight</b> (Codex `019e3f82` absorb): ISO-8859-9 dışı
 * karakter (emoji, CJK, smart quote) içeren mesaj — her iki transport'ta da —
 * {@link SmsFailureClass#UNSUPPORTED_CHARSET} ile reddedilir; silent
 * transliteration YAPILMAZ. {@link SmsAdapter} Unicode destekleyen
 * secondary'ye (NetGSM) charset-capability pre-route eder.
 *
 * <h3>Response</h3>
 *
 * <p>HTTP: plain-text {@code Status=0 MessageIDs=756464245}. SOAP:
 * {@code SendSMSResult} ({@code ErrorCode}+{@code ID}; {@code ErrorCode}
 * {@code 00} ile başlarsa başarı). ACCEPTED correlator her iki transport'ta
 * {@code "jetsms-<id>"}.
 *
 * <h3>DLR — POLL</h3>
 *
 * <p>JetSMS DLR webhook GÖNDERMEZ; backend periyodik poll eder
 * ({@link #dlrMode()} = {@link SmsProvider.SmsDlrMode#POLL},
 * {@code JetSmsDlrPollingWorker}). HTTP: {@code HttpSmsReport}
 * ({@code MessageIDs} batch). SOAP: {@code ReportSMS} ({@code groupid}
 * başına sorgu — orchestrator alıcı-başına tek SMS gönderdiği için her
 * {@code ID} bir group'tur).
 */
@Component
public class JetSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(JetSmsProvider.class);
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int RESPONSE_TIMEOUT_SEC = 15;
    public static final String PROVIDER_KEY = "jetsms";

    /** JetSMS ek yardım dosyası: bu karakterler %XX hex'e çevrilir, diğerleri raw. */
    private static final String RESERVED_CHARS = "!*'();:@&=+$,/?%#[]";
    /** JetSMS HTTP body charset — Türkçe Latin-5. */
    static final Charset ISO_8859_9 = Charset.forName("ISO-8859-9");
    /** JetSMS tek mesaj uzunluk limiti (dokümandan). */
    private static final int MAX_MESSAGE_LENGTH = 160;

    /** Transport seçimi — {@code soap} (default) ya da {@code http}. */
    static final String TRANSPORT_SOAP = "soap";
    static final String TRANSPORT_HTTP = "http";
    /** SOAP servis namespace — {@code soapSMS.asmx} ASP.NET (tempuri.org). */
    private static final String SOAP_NS = "http://tempuri.org/";
    /** {@code onlengthproblem} — REQUIRED (WSDL minOccurs=1); HTTP davranışıyla
     *  paritede tüm paketi reddet (mesaj zaten 160-char preflight'tan geçti). */
    private static final String SOAP_ON_LENGTH_PROBLEM = "RejectAllPackage";
    /** {@code ReportSMS} status param: {@code 5} = tüm durum kodları. */
    private static final String SOAP_REPORT_STATUS_ALL = "5";

    @Value("${notify.adapters.sms.jetsms.api-url:https://api.jetsms.com.tr/SMS-Web/HttpSmsSend}")
    private String apiUrl;

    @Value("${notify.adapters.sms.jetsms.report-url:https://api.jetsms.com.tr/SMS-Web/HttpSmsReport}")
    private String reportUrl;

    /**
     * SOAP servis endpoint — {@code soapSMS.asmx}. {@code SendSMS} ve
     * {@code ReportSMS} aynı .asmx'e POST edilir (SOAPAction header ayırır).
     */
    @Value("${notify.adapters.sms.jetsms.soap-url:https://api.jetsms.com.tr/ws/soapSMS.asmx}")
    private String soapUrl;

    /**
     * Aktif transport — {@code soap} (default, Faz 23.4 cutover) | {@code http}.
     * Bilinmeyen/blank değer {@code soap}'a düşer (fail-safe default).
     */
    @Value("${notify.adapters.sms.jetsms.transport:soap}")
    private String transport;

    @Value("${notify.adapters.sms.jetsms.username:}")
    private String username;

    @Value("${notify.adapters.sms.jetsms.password:}")
    private String password;

    @Value("${notify.adapters.sms.jetsms.originator:}")
    private String originator;

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public SmsDlrMode dlrMode() {
        return SmsDlrMode.POLL;
    }

    @Override
    public boolean supportsUnicode() {
        return false;  // JetSMS ISO-8859-9 — Unicode (emoji/CJK) desteklemez
    }

    @Override
    public SmsSendResult send(String e164Phone, String text) {
        // 1. Config gate — username/originator boşsa fail-closed (PROVIDER_CONFIG).
        // Codex 019e421f iter-1 P2 absorb: password de required (SOAP envelope
        // ve HTTP form body ikisi de password gönderiyor; default boş — eksikse
        // fail-closed). Hem SOAP hem HTTP transport için dispatch-öncesi gate.
        if (username == null || username.isBlank()
            || password == null || password.isBlank()
            || originator == null || originator.isBlank()) {
            log.warn("jetsms config missing: username/password/originator empty");
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_CONFIG, "config");
        }
        // 2. Empty text guard.
        if (text == null || text.isBlank()) {
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.EMPTY_MESSAGE, null);
        }
        // 3. Message length guard (JetSMS 160 char limit).
        if (text.length() > MAX_MESSAGE_LENGTH) {
            log.warn("jetsms message too long: len={} max={}", text.length(), MAX_MESSAGE_LENGTH);
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.MESSAGE_TOO_LONG,
                "len" + text.length());
        }
        // 4. Charset preflight — ISO-8859-9 encode edilemiyorsa UNSUPPORTED_CHARSET
        //    (Codex absorb: silent transliteration YOK; SmsAdapter Unicode
        //    destekleyen secondary'ye pre-route eder). Hem SOAP hem HTTP
        //    transport için zorunlu — JetSMS hesabı her iki yüzeyde de
        //    ISO-8859-9 Latin-5 charset.
        if (!ISO_8859_9.newEncoder().canEncode(text)) {
            log.warn("jetsms text not ISO-8859-9 encodable — UNSUPPORTED_CHARSET");
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.UNSUPPORTED_CHARSET,
                "charset");
        }

        // 5. Phone format — JetSMS "90..." (+ strip, no leading +).
        String phone = (e164Phone != null && e164Phone.startsWith("+"))
            ? e164Phone.substring(1) : e164Phone;

        // 6. Transport dispatch — soap (default, Faz 23.4) | http (additive).
        if (isHttpTransport()) {
            return sendViaHttp(phone, text);
        }
        return sendViaSoap(phone, text);
    }

    /**
     * Faz 23.3 PR-2 HTTP API send — {@code SMS-Web/HttpSmsSend}.
     * form-urlencoded body + plain-text {@code Status=X MessageIDs=Y} parse.
     *
     * <p>Davranış birebir korunur (Faz 23.4 SOAP cutover sırasında HTTP path
     * Biotekno provisioning sonrası kullanıma hazır kalmalı).
     */
    private SmsSendResult sendViaHttp(String phone, String text) {
        // form-urlencoded body — custom encode (JetSMS reserved-set), tüm
        // body ISO-8859-9 byte'a StringEntity charset ile çevrilir.
        String body = "Username=" + jetEncode(username)
            + "&Password=" + jetEncode(password)
            + "&Msisdns=" + jetEncode(phone)
            + "&Messages=" + jetEncode(text)
            + "&Originator=" + jetEncode(originator);

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(apiUrl);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=ISO-8859-9");
            post.setEntity(new StringEntity(body, ISO_8859_9));

            return client.execute(post, response -> {
                int httpCode = response.getCode();
                String respBody = readEntityBody(response, ISO_8859_9);

                // HTTP-level dispatch.
                if (httpCode >= 500) {
                    log.warn("jetsms HTTP transport transient HTTP RETRY: code={}", httpCode);
                    return SmsSendResult.retry(PROVIDER_KEY, SmsFailureClass.HTTP_5XX,
                        "http" + httpCode);
                }
                if (httpCode == 401 || httpCode == 403) {
                    log.warn("jetsms HTTP transport auth FAIL: code={}", httpCode);
                    return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_CONFIG,
                        "http" + httpCode);
                }
                if (httpCode >= 400) {
                    log.warn("jetsms HTTP transport permanent HTTP FAIL: code={}", httpCode);
                    return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_SYSTEM,
                        "http" + httpCode);
                }

                // Plain-text response parse: "Status=X MessageIDs=Y".
                if (respBody.isBlank()) {
                    log.warn("jetsms HTTP transport 2xx empty body (RETRY)");
                    return SmsSendResult.retry(PROVIDER_KEY,
                        SmsFailureClass.UNKNOWN_TRANSIENT, "empty-body");
                }
                return parseResponse(respBody);
            });
        } catch (IOException e) {
            log.warn("jetsms HTTP transport IOException (RETRY): {}",
                e.getClass().getSimpleName());
            return SmsSendResult.retry(PROVIDER_KEY, SmsFailureClass.TIMEOUT,
                "io:" + e.getClass().getSimpleName());
        }
    }

    /**
     * Faz 23.4 SOAP transport send — {@code soapSMS.asmx} {@code SendSMS}.
     *
     * <p>SOAP 1.1 zarf, {@code text/xml; charset=utf-8},
     * {@code SOAPAction: "http://tempuri.org/SendSMS"}. Tek-alıcı kullanım:
     * {@code messages} ve {@code receipents} tek-elemanlı {@code ArrayOfString}.
     * Response: {@code SendSMSResult} ({@code ErrorCode}+{@code ID}).
     * {@code ErrorCode} {@code 00} ile başlarsa başarı; {@code ID} group id
     * ({@code ReportSMS.groupid}) — correlator {@code "jetsms-<ID>"}.
     */
    private SmsSendResult sendViaSoap(String phone, String text) {
        String envelope = buildSendSoapEnvelope(username, password, originator, phone, text);

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(soapUrl);
            post.setHeader("Content-Type", "text/xml; charset=utf-8");
            post.setHeader("SOAPAction", "\"" + SOAP_NS + "SendSMS\"");
            post.setEntity(new StringEntity(envelope, java.nio.charset.StandardCharsets.UTF_8));

            return client.execute(post, response -> {
                int httpCode = response.getCode();
                String respBody = readEntityBody(response, java.nio.charset.StandardCharsets.UTF_8);

                // SOAP fault (HTTP 500) — provider tarafı yaygın olarak
                // soap:Fault'u 500 ile döndürür. Transient olarak işle.
                if (httpCode >= 500) {
                    log.warn("jetsms SOAP transport transient HTTP RETRY: code={} body={}",
                        httpCode, truncate(respBody, 200));
                    return SmsSendResult.retry(PROVIDER_KEY, SmsFailureClass.HTTP_5XX,
                        "http" + httpCode);
                }
                if (httpCode == 401 || httpCode == 403) {
                    log.warn("jetsms SOAP transport auth FAIL: code={}", httpCode);
                    return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_CONFIG,
                        "http" + httpCode);
                }
                if (httpCode >= 400) {
                    log.warn("jetsms SOAP transport permanent HTTP FAIL: code={}", httpCode);
                    return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_SYSTEM,
                        "http" + httpCode);
                }
                if (respBody.isBlank()) {
                    log.warn("jetsms SOAP transport 2xx empty body (RETRY)");
                    return SmsSendResult.retry(PROVIDER_KEY,
                        SmsFailureClass.UNKNOWN_TRANSIENT, "empty-body");
                }
                return parseSoapSendResponse(respBody);
            });
        } catch (IOException e) {
            log.warn("jetsms SOAP transport IOException (RETRY): {}",
                e.getClass().getSimpleName());
            return SmsSendResult.retry(PROVIDER_KEY, SmsFailureClass.TIMEOUT,
                "io:" + e.getClass().getSimpleName());
        }
    }

    /**
     * JetSMS DLR poll — {@code HttpSmsReport} (Faz 23.3 PR-3).
     *
     * <p>JetSMS DLR webhook GÖNDERMEZ; backend bu method ile periyodik poll
     * eder ({@code JetSmsDlrPollingWorker}). POST form-urlencoded:
     * {@code Username/Password/Originator/MessageIDs} ({@code |} ayraçlı).
     * Response: {@code Status=0 MessageStates=2|2}.
     *
     * <p>MessageState → {@link SmsDlrPollResult.DeliveryStatus}:
     * {@code 1}→DELIVERED; {@code 3/4/9}→FAILED; {@code 0/2/6/7/8}→PENDING
     * (yeniden poll); {@code -1}→FAILED (yanlış MessageID — correlator
     * güvenilemez, terminal FAILED).
     *
     * @param providerMsgIds raw JetSMS message ID listesi ({@code "jetsms-"}
     *                       prefix ÇIKARILMIŞ)
     * @return her input ID için DLR durumu (sıra input ile aynı; eşleşmeyen
     *         ID PENDING-fallback)
     */
    @Override
    public java.util.List<SmsDlrPollResult> pollDelivery(java.util.List<String> providerMsgIds) {
        if (providerMsgIds == null || providerMsgIds.isEmpty()) {
            return java.util.List.of();
        }
        // Codex 019e421f iter-1 P2 absorb: password de required (her iki
        // transport da password gönderiyor; eksikse provider auth drift'i
        // PENDING/no-result olarak maskelenmesin).
        if (username == null || username.isBlank()
            || password == null || password.isBlank()
            || originator == null || originator.isBlank()) {
            log.warn("jetsms DLR poll config missing — pending fallback");
            return providerMsgIds.stream()
                .map(id -> SmsDlrPollResult.pending(id, "config"))
                .toList();
        }

        // Transport dispatch — soap (default) | http (additive, batched).
        if (isHttpTransport()) {
            return pollViaHttp(providerMsgIds);
        }
        return pollViaSoap(providerMsgIds);
    }

    /**
     * Faz 23.3 PR-3 HTTP DLR poll — {@code SMS-Web/HttpSmsReport}.
     * Batched: {@code MessageIDs} {@code |}-separated; response
     * {@code Status=0 MessageStates=2|2}.
     */
    private java.util.List<SmsDlrPollResult> pollViaHttp(java.util.List<String> providerMsgIds) {
        String joined = String.join("|", providerMsgIds);
        String body = "Username=" + jetEncode(username)
            + "&Password=" + jetEncode(password)
            + "&Originator=" + jetEncode(originator)
            + "&MessageIDs=" + jetEncode(joined);

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(reportUrl);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=ISO-8859-9");
            post.setEntity(new StringEntity(body, ISO_8859_9));

            return client.execute(post, response -> {
                int httpCode = response.getCode();
                String respBody = readEntityBody(response, ISO_8859_9);
                if (httpCode >= 400 || respBody.isBlank()) {
                    log.warn("jetsms HTTP DLR poll HTTP {} / empty — pending fallback", httpCode);
                    return providerMsgIds.stream()
                        .map(id -> SmsDlrPollResult.pending(id, "http" + httpCode))
                        .toList();
                }
                return parseReportResponse(providerMsgIds, respBody);
            });
        } catch (IOException e) {
            log.warn("jetsms HTTP DLR poll IOException ({}) — pending fallback",
                e.getClass().getSimpleName());
            return providerMsgIds.stream()
                .map(id -> SmsDlrPollResult.pending(id, "io"))
                .toList();
        }
    }

    /**
     * Faz 23.4 SOAP DLR poll — {@code soapSMS.asmx} {@code ReportSMS}.
     *
     * <p>SOAP {@code ReportSMS} {@code groupid} başına çalışır (HTTP API'deki
     * çoklu-ID batch yok). Orchestrator alıcı-başına tek SMS gönderdiği için
     * her input {@code rawId} bir {@code groupid}'tir; ID başına ayrı SOAP
     * çağrısı yapılır ({@code status=5} → tüm durum kodları).
     *
     * <p>Tek bir group için response birden fazla {@code ReportSMSResult}
     * dönebilir (paket bölündüyse): en {@code DELIVERED}-baskın durum
     * seçilir (DELIVERED > FAILED > PENDING). Network/parse hatası alan ID
     * pending döner — sonraki cycle yeniden denenir.
     */
    private java.util.List<SmsDlrPollResult> pollViaSoap(java.util.List<String> providerMsgIds) {
        java.util.List<SmsDlrPollResult> results = new java.util.ArrayList<>(providerMsgIds.size());
        for (String rawId : providerMsgIds) {
            results.add(pollSoapOneGroup(rawId));
        }
        return results;
    }

    /** Tek bir {@code groupid} için {@code ReportSMS} çağrısı. */
    private SmsDlrPollResult pollSoapOneGroup(String groupId) {
        String envelope = buildReportSoapEnvelope(username, password, groupId,
            SOAP_REPORT_STATUS_ALL);

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(soapUrl);
            post.setHeader("Content-Type", "text/xml; charset=utf-8");
            post.setHeader("SOAPAction", "\"" + SOAP_NS + "ReportSMS\"");
            post.setEntity(new StringEntity(envelope, java.nio.charset.StandardCharsets.UTF_8));

            return client.execute(post, response -> {
                int httpCode = response.getCode();
                String respBody = readEntityBody(response, java.nio.charset.StandardCharsets.UTF_8);
                if (httpCode >= 400 || respBody.isBlank()) {
                    log.warn("jetsms SOAP DLR poll HTTP {} / empty for group={} — pending",
                        httpCode, groupId);
                    return SmsDlrPollResult.pending(groupId, "http" + httpCode);
                }
                return parseSoapReportResponse(groupId, respBody);
            });
        } catch (IOException e) {
            log.warn("jetsms SOAP DLR poll IOException ({}) group={} — pending",
                e.getClass().getSimpleName(), groupId);
            return SmsDlrPollResult.pending(groupId, "io");
        }
    }

    /**
     * {@code HttpSmsReport} response parse: {@code Status=0 MessageStates=2|2}.
     * MessageStates input MessageIDs ile aynı sırada; index-eşleme.
     */
    static java.util.List<SmsDlrPollResult> parseReportResponse(
            java.util.List<String> providerMsgIds, String body) {
        String statusRaw = extractField(body, "Status");
        int status;
        try {
            status = Integer.parseInt(statusRaw.trim());
        } catch (NumberFormatException nfe) {
            log.warn("jetsms DLR poll unparseable Status — pending fallback");
            return providerMsgIds.stream()
                .map(id -> SmsDlrPollResult.pending(id, "bad-status"))
                .toList();
        }
        if (status != 0) {
            // Status -3 (bad MessageID format) / -5 (login) / -15 / -99 →
            // bu batch poll edilemedi; tümü pending, sonraki cycle tekrar.
            log.warn("jetsms DLR poll Status={} — pending fallback (batch retry)", status);
            return providerMsgIds.stream()
                .map(id -> SmsDlrPollResult.pending(id, String.valueOf(status)))
                .toList();
        }

        String statesRaw = extractField(body, "MessageStates");
        String[] states = statesRaw.isEmpty()
            ? new String[0] : statesRaw.split("\\|", -1);

        java.util.List<SmsDlrPollResult> results = new java.util.ArrayList<>(providerMsgIds.size());
        for (int i = 0; i < providerMsgIds.size(); i++) {
            String rawId = providerMsgIds.get(i);
            if (i >= states.length) {
                // Response state count input'tan az — eşleşmeyen ID pending.
                results.add(SmsDlrPollResult.pending(rawId, "no-state"));
                continue;
            }
            results.add(mapMessageState(rawId, states[i].trim()));
        }
        return results;
    }

    /**
     * JetSMS MessageState kodu → {@link SmsDlrPollResult}.
     *
     * <p>{@code 1}→DELIVERED; {@code 3/4/9}→FAILED; {@code 0/2/6/7/8}→PENDING;
     * {@code -1}→FAILED (yanlış MessageID); unparseable→PENDING.
     */
    static SmsDlrPollResult mapMessageState(String rawId, String stateRaw) {
        int state;
        try {
            state = Integer.parseInt(stateRaw);
        } catch (NumberFormatException nfe) {
            return SmsDlrPollResult.pending(rawId, "bad-state");
        }
        return switch (state) {
            case 1 -> SmsDlrPollResult.delivered(rawId, "1");
            case 3, 4, 9 -> SmsDlrPollResult.failed(rawId, String.valueOf(state));
            case -1 -> SmsDlrPollResult.failed(rawId, "-1");  // wrong MessageID
            case 0, 2, 6, 7, 8 -> SmsDlrPollResult.pending(rawId, String.valueOf(state));
            default -> SmsDlrPollResult.pending(rawId, String.valueOf(state));
        };
    }

    /**
     * JetSMS plain-text response parse: {@code Status=<int> MessageIDs=<id>}.
     *
     * <p>Status 0 + MessageID positive → ACCEPTED. Status/MessageID negatif →
     * {@link SmsFailureClass} mapping.
     */
    static SmsSendResult parseResponse(String body) {
        String statusRaw = extractField(body, "Status");
        String messageIdRaw = extractField(body, "MessageIDs");

        int status;
        try {
            status = Integer.parseInt(statusRaw.trim());
        } catch (NumberFormatException nfe) {
            log.warn("jetsms unparseable Status (RETRY): raw_len={}", statusRaw.length());
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.UNKNOWN_TRANSIENT, "bad-status");
        }

        // Status != 0 — send-level hata.
        if (status != 0) {
            SmsFailureClass cls = statusFailureClass(status);
            if (cls.failoverEligible()) {
                log.warn("jetsms send Status={} → RETRY/{}", status, cls);
                return SmsSendResult.retry(PROVIDER_KEY, cls, String.valueOf(status));
            }
            log.warn("jetsms send Status={} → FAILED/{}", status, cls);
            return SmsSendResult.failed(PROVIDER_KEY, cls, String.valueOf(status));
        }

        // Status == 0 — MessageID kontrol (-1 invalid msisdn, -2 invalid text,
        // çoklu gönderimde "|" ayraçlı; biz tek msisdn → ilk segment).
        String firstId = messageIdRaw.contains("|")
            ? messageIdRaw.substring(0, messageIdRaw.indexOf('|')) : messageIdRaw;
        firstId = firstId.trim();
        int messageId;
        try {
            messageId = Integer.parseInt(firstId);
        } catch (NumberFormatException nfe) {
            log.warn("jetsms Status=0 but unparseable MessageID (RETRY)");
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.NO_CORRELATOR, "bad-msgid");
        }
        if (messageId == -1) {
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.INVALID_PHONE, "msgid-1");
        }
        if (messageId == -2) {
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.INVALID_TEXT, "msgid-2");
        }
        if (messageId <= 0) {
            log.warn("jetsms Status=0 non-positive MessageID={} (RETRY)", messageId);
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.NO_CORRELATOR, "msgid" + messageId);
        }
        // Geçerli MessageID → ACCEPTED (terminal durum DLR polling ile gelir).
        String correlator = PROVIDER_KEY + "-" + messageId;
        log.info("jetsms ACCEPTED (awaits DLR poll): msg_id={}", correlator);
        return SmsSendResult.accepted(PROVIDER_KEY, correlator);
    }

    /**
     * JetSMS send Status kodu → {@link SmsFailureClass}.
     *
     * <p>-5 login / -6 data / -7 senddate / -8 msisdn / -9 message → kalıcı;
     * -15 system / -99 unknown → transient.
     */
    static SmsFailureClass statusFailureClass(int status) {
        return switch (status) {
            case -5 -> SmsFailureClass.PROVIDER_CONFIG;   // login error (username/password/originator)
            case -6 -> SmsFailureClass.PROVIDER_SYSTEM;   // data error
            case -7 -> SmsFailureClass.INVALID_TEXT;      // senddate error
            case -8 -> SmsFailureClass.INVALID_PHONE;     // msisdn missing
            case -9 -> SmsFailureClass.EMPTY_MESSAGE;     // message missing
            case -15 -> SmsFailureClass.PROVIDER_SYSTEM;  // system fault → transient
            default -> SmsFailureClass.UNKNOWN_TRANSIENT; // -99 ve bilinmeyen
        };
    }

    /**
     * JetSMS field extract — plain-text {@code Key=Value} (boşluk/newline ayraçlı).
     * Yoksa boş string.
     */
    static String extractField(String body, String key) {
        int idx = body.indexOf(key + "=");
        if (idx < 0) return "";
        int start = idx + key.length() + 1;
        int end = start;
        while (end < body.length()
            && !Character.isWhitespace(body.charAt(end))) {
            end++;
        }
        return body.substring(start, end);
    }

    /**
     * JetSMS custom URL-encode (ek yardım dosyası): yalnız
     * {@link #RESERVED_CHARS} {@code %XX} hex'e çevrilir; diğerleri (Türkçe
     * dahil) raw bırakılır. Standart {@code URLEncoder} (UTF-8 + boşluk→+)
     * KULLANILMAZ.
     */
    static String jetEncode(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (RESERVED_CHARS.indexOf(c) >= 0) {
                sb.append('%').append(String.format("%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String readEntityBody(org.apache.hc.core5.http.ClassicHttpResponse response,
                                         Charset charset)
            throws IOException {
        if (response.getEntity() == null) return "";
        try (var stream = response.getEntity().getContent()) {
            if (stream == null) return "";
            return new String(stream.readAllBytes(), charset);
        }
    }

    private static CloseableHttpClient newClient() {
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .setResponseTimeout(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build();
        return HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    // ─────────────────────────── SOAP helpers ────────────────────────────

    /** {@code transport=http} mu? Blank/bilinmeyen değer SOAP'a düşer (default). */
    boolean isHttpTransport() {
        return transport != null && TRANSPORT_HTTP.equalsIgnoreCase(transport.trim());
    }

    /**
     * {@code SendSMS} SOAP 1.1 zarfı oluşturur — tek alıcı + tek mesaj.
     *
     * <p>Tüm metin değerleri {@link #xmlEscape} ile XML-escape edilir.
     * {@code onlengthproblem} WSDL'de {@code minOccurs=1} (REQUIRED); pre-flight
     * 160-char guard zaten varken {@code RejectAllPackage} (HTTP davranışıyla
     * paritede).
     */
    static String buildSendSoapEnvelope(String user, String pass, String originator,
                                        String phone, String text) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        sb.append("<soap:Envelope")
          .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
          .append(" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"")
          .append(" xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        sb.append("<soap:Body>");
        sb.append("<SendSMS xmlns=\"").append(SOAP_NS).append("\">");
        sb.append("<user>").append(xmlEscape(user)).append("</user>");
        sb.append("<password>").append(xmlEscape(pass)).append("</password>");
        sb.append("<originator>").append(xmlEscape(originator)).append("</originator>");
        sb.append("<reference></reference>");
        sb.append("<startdate></startdate>");
        sb.append("<expiredate></expiredate>");
        sb.append("<messages><string>").append(xmlEscape(text)).append("</string></messages>");
        sb.append("<receipents><string>").append(xmlEscape(phone)).append("</string></receipents>");
        sb.append("<onlengthproblem>").append(SOAP_ON_LENGTH_PROBLEM).append("</onlengthproblem>");
        sb.append("</SendSMS>");
        sb.append("</soap:Body>");
        sb.append("</soap:Envelope>");
        return sb.toString();
    }

    /**
     * {@code ReportSMS} SOAP 1.1 zarfı — tek groupid sorgusu, {@code status=5}
     * (tüm durum kodları).
     */
    static String buildReportSoapEnvelope(String user, String pass, String groupId,
                                          String status) {
        StringBuilder sb = new StringBuilder(384);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        sb.append("<soap:Envelope")
          .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
          .append(" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"")
          .append(" xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        sb.append("<soap:Body>");
        sb.append("<ReportSMS xmlns=\"").append(SOAP_NS).append("\">");
        sb.append("<user>").append(xmlEscape(user)).append("</user>");
        sb.append("<password>").append(xmlEscape(pass)).append("</password>");
        sb.append("<groupid>").append(xmlEscape(groupId)).append("</groupid>");
        sb.append("<status>").append(xmlEscape(status)).append("</status>");
        sb.append("</ReportSMS>");
        sb.append("</soap:Body>");
        sb.append("</soap:Envelope>");
        return sb.toString();
    }

    /**
     * {@code SendSMSResponse} parse:
     * {@code <SendSMSResult><ErrorCode>00 ...</ErrorCode><ID>123...</ID></SendSMSResult>}.
     *
     * <p>{@code ErrorCode} {@code 00} (veya {@code 00 ...}) ile başlarsa
     * başarı + {@code ID} correlator olur. Diğer kodlar (örn. {@code 03},
     * {@code 10}, {@code 28}) {@link SmsFailureClass} mapping'i ile fail
     * döndürür.
     */
    static SmsSendResult parseSoapSendResponse(String body) {
        // soap:Fault — provider tarafı 2xx ile fault dönerse (gözlenmeden,
        // ama defansif): faultcode/faultstring varsa transient retry.
        if (body.contains("<soap:Fault") || body.contains("<faultcode")) {
            log.warn("jetsms SOAP send fault response (RETRY): body_len={}", body.length());
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.PROVIDER_SYSTEM, "soap-fault");
        }
        String errorCode = extractXmlValue(body, "ErrorCode");
        String id = extractXmlValue(body, "ID");

        if (errorCode == null || errorCode.isBlank()) {
            // SOAP ErrorCode WSDL'de minOccurs=0; ama gerçek response'ta olmalı.
            // Yoksa response shape unknown → transient retry.
            log.warn("jetsms SOAP send response missing ErrorCode (RETRY)");
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.UNKNOWN_TRANSIENT, "no-errorcode");
        }
        String code = errorCode.trim();
        // "00" success prefix (ör. "00", "00 130228114512"). Aksi → fail/retry.
        if (!code.startsWith("00")) {
            SmsFailureClass cls = soapErrorCodeFailureClass(code);
            if (cls.failoverEligible()) {
                log.warn("jetsms SOAP send ErrorCode={} → RETRY/{}", code, cls);
                return SmsSendResult.retry(PROVIDER_KEY, cls, code);
            }
            log.warn("jetsms SOAP send ErrorCode={} → FAILED/{}", code, cls);
            return SmsSendResult.failed(PROVIDER_KEY, cls, code);
        }
        // ErrorCode 00 → ID positive numeric beklenir (group id).
        if (id == null || id.isBlank()) {
            log.warn("jetsms SOAP send ErrorCode=00 but missing ID (RETRY)");
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.NO_CORRELATOR, "no-id");
        }
        String trimmedId = id.trim();
        // Defansif: ID alphanumeric/dot olabilir mi? JetSMS sample'lar pure
        // numeric ("110906180000526", "756464245"). Boş değilse kabul, sadece
        // negatif numeric'i NO_CORRELATOR olarak işle.
        try {
            long parsed = Long.parseLong(trimmedId);
            if (parsed <= 0) {
                log.warn("jetsms SOAP send non-positive ID={} (RETRY)", trimmedId);
                return SmsSendResult.retry(PROVIDER_KEY,
                    SmsFailureClass.NO_CORRELATOR, "id" + trimmedId);
            }
        } catch (NumberFormatException nfe) {
            // Non-numeric ID — defansif olarak yine kabul; correlator string olarak taşınır.
            log.warn("jetsms SOAP send non-numeric ID accepted: {}", trimmedId);
        }
        String correlator = PROVIDER_KEY + "-" + trimmedId;
        log.info("jetsms SOAP ACCEPTED (awaits DLR poll): msg_id={}", correlator);
        return SmsSendResult.accepted(PROVIDER_KEY, correlator);
    }

    /**
     * JetSMS SOAP {@code ErrorCode} → {@link SmsFailureClass}.
     * Dönüş kodları dokümanı (2022-11-09) baz alınır.
     *
     * <ul>
     *   <li>{@code 03} (user/password empty), {@code 10} (wrong user),
     *       {@code 12} (wrong title), {@code 28} (unauthorized IP),
     *       {@code 30} (originator missing) → {@link SmsFailureClass#PROVIDER_CONFIG}</li>
     *   <li>{@code 11} (insufficient credit) → {@link SmsFailureClass#QUOTA_OR_CREDIT}</li>
     *   <li>{@code 27}, {@code 31} (phone missing) → {@link SmsFailureClass#INVALID_PHONE}</li>
     *   <li>{@code 38} (text missing) → {@link SmsFailureClass#EMPTY_MESSAGE}</li>
     *   <li>{@code 80}, {@code 90} (XML/charset error) → {@link SmsFailureClass#INVALID_TEXT}</li>
     *   <li>{@code 81}, {@code 84} (array mismatch / count zero) → {@link SmsFailureClass#PROVIDER_SYSTEM}</li>
     *   <li>diğer → {@link SmsFailureClass#UNKNOWN_TRANSIENT}</li>
     * </ul>
     */
    static SmsFailureClass soapErrorCodeFailureClass(String code) {
        if (code == null) return SmsFailureClass.UNKNOWN_TRANSIENT;
        // Some codes appear with descriptive suffix ("03 Password must be fill") —
        // strip whitespace and take the leading numeric token.
        String head = code.trim();
        int sp = head.indexOf(' ');
        if (sp > 0) head = head.substring(0, sp);
        return switch (head) {
            case "03", "10", "12", "13", "14", "15", "16", "17",
                 "28", "30", "61", "62", "72", "76", "77" -> SmsFailureClass.PROVIDER_CONFIG;
            case "11" -> SmsFailureClass.QUOTA_OR_CREDIT;
            case "27", "31" -> SmsFailureClass.INVALID_PHONE;
            case "38" -> SmsFailureClass.EMPTY_MESSAGE;
            case "80", "90" -> SmsFailureClass.INVALID_TEXT;
            case "81", "84" -> SmsFailureClass.PROVIDER_SYSTEM;
            default -> SmsFailureClass.UNKNOWN_TRANSIENT;
        };
    }

    /**
     * {@code ReportSMSResponse} parse — tek group için, tüm
     * {@code ReportSMSResult} entry'leri tarayıp en ileri durumu seçer
     * (DELIVERED > FAILED > PENDING).
     *
     * <p>JetSMS Status: 1=delivered, 3=failed, 4=failed(timeout),
     * 0/2/6/7/8=pending. Boş array (henüz operatöre verilmemiş) → pending.
     */
    static SmsDlrPollResult parseSoapReportResponse(String groupId, String body) {
        if (body.contains("<soap:Fault") || body.contains("<faultcode")) {
            log.warn("jetsms SOAP DLR fault for group={} — pending fallback", groupId);
            return SmsDlrPollResult.pending(groupId, "soap-fault");
        }
        // Inner <ReportSMSResult> entry'lerinden <Status> değerlerini çıkar.
        // Note: outer wrapper de <ReportSMSResult> (array name); biz inner-only
        // taramayı <Status> extraction'a bırakıyoruz (outer'da Status alanı yok).
        java.util.List<String> statuses = extractAllXmlValues(body, "Status");
        if (statuses.isEmpty()) {
            // Henüz hiçbir result yok (operator raporu gelmemiş) — pending.
            return SmsDlrPollResult.pending(groupId, "no-result");
        }
        // Aggregate: any DELIVERED → delivered; else any FAILED → failed; else pending.
        // Codex 019e421f iter-1 P3 absorb: winning bucket'in providerStateCode'unu
        // döner — aggregation kararı (terminal class) ve reported status code
        // TUTARLI olmalı; aksi halde audit/DLR-ingest yanıltıcı kod görür (örn.
        // Status=1,2 → DELIVERED ama providerStateCode=2 yazılırdı). Her bucket
        // için İLK eşleşen kodu winning kabul ederiz.
        String deliveredCode = null;
        String failedCode = null;
        String pendingCode = null;
        for (String s : statuses) {
            String t = s.trim();
            int v;
            try {
                v = Integer.parseInt(t);
            } catch (NumberFormatException nfe) {
                continue;  // unparseable — skip
            }
            if (v == 1) {
                if (deliveredCode == null) deliveredCode = t;
            } else if (v == 3 || v == 4 || v == 9) {
                if (failedCode == null) failedCode = t;
            } else {
                // 0, 2, 6, 7, 8 → pending
                if (pendingCode == null) pendingCode = t;
            }
        }
        if (deliveredCode != null) {
            return SmsDlrPollResult.delivered(groupId, deliveredCode);
        }
        if (failedCode != null) {
            return SmsDlrPollResult.failed(groupId, failedCode);
        }
        return SmsDlrPollResult.pending(groupId, pendingCode);
    }

    /**
     * XML element içeriği extract — ilk eşleşme. Namespace prefix yok sayılır
     * (örn. hem {@code <ID>} hem {@code <ns:ID>} eşleşir). Self-closing
     * ({@code <ID/>}) → boş string.
     *
     * <p>Mevcut HTTP {@code extractField} pattern'iyle aynı disiplinde:
     * lightweight string scan, full XML DOM yüklenmez.
     */
    static String extractXmlValue(String body, String localName) {
        if (body == null || localName == null) return null;
        // Open tag: <localName ...> veya <prefix:localName ...>
        int start = 0;
        while (true) {
            int open = body.indexOf("<", start);
            if (open < 0) return null;
            int gt = body.indexOf('>', open);
            if (gt < 0) return null;
            String tag = body.substring(open + 1, gt);
            // Self-closing? tag ends with '/'
            boolean selfClosing = tag.endsWith("/");
            if (selfClosing) tag = tag.substring(0, tag.length() - 1).trim();
            // Strip attributes
            int sp = tag.indexOf(' ');
            String nameRaw = (sp > 0) ? tag.substring(0, sp) : tag;
            // Strip namespace prefix
            int colon = nameRaw.indexOf(':');
            String name = (colon >= 0) ? nameRaw.substring(colon + 1) : nameRaw;
            if (name.equals(localName)) {
                if (selfClosing) return "";
                // Find matching close tag (ignore namespace prefix).
                int closeStart = body.indexOf("</", gt + 1);
                while (closeStart >= 0) {
                    int closeGt = body.indexOf('>', closeStart);
                    if (closeGt < 0) return null;
                    String closeTag = body.substring(closeStart + 2, closeGt).trim();
                    int closeColon = closeTag.indexOf(':');
                    String closeName = (closeColon >= 0) ? closeTag.substring(closeColon + 1) : closeTag;
                    if (closeName.equals(localName)) {
                        return xmlUnescape(body.substring(gt + 1, closeStart));
                    }
                    closeStart = body.indexOf("</", closeGt + 1);
                }
                return null;
            }
            start = gt + 1;
        }
    }

    /**
     * Tüm {@code <localName>...</localName>} değerlerini sırasıyla döner
     * (namespace prefix yoksayılır). {@link #parseSoapReportResponse}
     * tek group'a karşılık çok ReportSMSResult olabilen response için kullanır.
     */
    static java.util.List<String> extractAllXmlValues(String body, String localName) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (body == null || localName == null) return out;
        int cursor = 0;
        while (cursor < body.length()) {
            int open = body.indexOf("<", cursor);
            if (open < 0) break;
            int gt = body.indexOf('>', open);
            if (gt < 0) break;
            String tag = body.substring(open + 1, gt);
            boolean selfClosing = tag.endsWith("/");
            if (selfClosing) tag = tag.substring(0, tag.length() - 1).trim();
            int sp = tag.indexOf(' ');
            String nameRaw = (sp > 0) ? tag.substring(0, sp) : tag;
            int colon = nameRaw.indexOf(':');
            String name = (colon >= 0) ? nameRaw.substring(colon + 1) : nameRaw;
            if (name.equals(localName)) {
                if (selfClosing) {
                    out.add("");
                    cursor = gt + 1;
                    continue;
                }
                int closeStart = body.indexOf("</" + nameRaw + ">", gt + 1);
                // Try with bare local name too if prefixed lookup misses.
                if (closeStart < 0) {
                    closeStart = body.indexOf("</" + localName + ">", gt + 1);
                }
                if (closeStart < 0) break;
                out.add(xmlUnescape(body.substring(gt + 1, closeStart)));
                cursor = closeStart + 1;
            } else {
                cursor = gt + 1;
            }
        }
        return out;
    }

    /**
     * Minimal XML escape — yalnız {@code &}, {@code <}, {@code >},
     * {@code "}, {@code '} dönüştürülür. JetSMS SOAP utf-8 zarf, ISO-8859-9
     * preflight (Türkçe karakterler raw geçer).
     */
    static String xmlEscape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Minimal XML un-escape — extractXmlValue içeriği parse için ters yön. */
    static String xmlUnescape(String value) {
        if (value == null || value.indexOf('&') < 0) return value;
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
