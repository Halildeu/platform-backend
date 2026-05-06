package com.serban.notify.adapter.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * NetGSM SMS adapter (Faz 23.3.1 — Production MVP geniş scope).
 *
 * <p>NetGSM REST API: https://api.netgsm.com.tr/sms/rest/v2/send
 *
 * <p>Channel addressing: recipient-addressed (her recipient delivery row,
 * email pattern paralel). DeliveryTarget.targetRef = E.164 phone number
 * (e.g. +905321234567).
 *
 * <p>Status semantics:
 * <ul>
 *   <li>HTTP 2xx + provider success code → DELIVERED</li>
 *   <li>HTTP 4xx OR provider permanent error code → FAILED (invalid number,
 *       insufficient credit, sender ID rejected)</li>
 *   <li>HTTP 5xx / timeout / IOException → RETRY (PR4 worker)</li>
 * </ul>
 *
 * <p>Provider error codes (NetGSM common):
 * <ul>
 *   <li>00 — success</li>
 *   <li>20 — message too long (segment limit)</li>
 *   <li>30 — invalid username/password</li>
 *   <li>40 — invalid msgheader (sender ID)</li>
 *   <li>50 — invalid phone number format</li>
 *   <li>60 — insufficient credit</li>
 *   <li>70 — IYS opt-out (recipient blocked)</li>
 * </ul>
 *
 * <p>Encoding: SmsSegmentEncoder detects GSM-7 vs UCS-2; provider param
 * "TR" for UCS-2 (Turkish chars), "" for GSM-7.
 */
@Component
public class NetGsmSmsAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(NetGsmSmsAdapter.class);
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int RESPONSE_TIMEOUT_SEC = 15;
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private final ObjectMapper objectMapper;

    @Value("${notify.adapters.sms.netgsm.api-url:https://api.netgsm.com.tr/sms/rest/v2/send}")
    private String apiUrl;

    @Value("${notify.adapters.sms.netgsm.username:}")
    private String username;

    @Value("${notify.adapters.sms.netgsm.password:}")
    private String password;

    @Value("${notify.adapters.sms.netgsm.msgheader:Notify}")
    private String msgheader;

    public NetGsmSmsAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String channelKey() {
        return "sms";
    }

    @Override
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        // 1. Validation
        if (username == null || username.isBlank()) {
            log.warn("netgsm config missing: username empty");
            return DeliveryAttemptResult.failed("netgsm credentials missing", null);
        }
        String phone = target.targetRef();
        if (phone == null || !E164.matcher(phone).matches()) {
            log.warn("netgsm invalid phone format: {}", phone);
            return DeliveryAttemptResult.failed("phone not E.164: " + phone, null);
        }
        String text = message.bodyText() != null && !message.bodyText().isBlank()
            ? message.bodyText()
            : message.subject();
        if (text == null || text.isBlank()) {
            return DeliveryAttemptResult.failed("empty message (no body_text or subject)", null);
        }

        // 2. Encoding + segment metadata (logged for billing audit)
        String encoding = SmsSegmentEncoder.encoding(text);
        int segments = SmsSegmentEncoder.segmentCount(text);
        log.info("netgsm send: phone=<redacted-hash:{}> encoding={} segments={} len={}",
            target.recipientHash(), encoding, segments, text.length());

        // 3. NetGSM REST v2 payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("msgheader", msgheader);
        payload.put("encoding", "UCS-2".equals(encoding) ? "TR" : "");
        Map<String, Object> message1 = new HashMap<>();
        message1.put("msg", text);
        message1.put("no", phone.startsWith("+") ? phone.substring(1) : phone);  // NetGSM strips +
        payload.put("messages", new Object[] { message1 });

        // 4. POST request
        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(apiUrl);
            post.setHeader("Content-Type", "application/json; charset=utf-8");
            post.setHeader("Authorization", basicAuth(username, password));
            String body = objectMapper.writeValueAsString(payload);
            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            String providerMsgId = "netgsm-" + UUID.randomUUID();
            return client.execute(post, response -> {
                int httpCode = response.getCode();
                String respBody = new String(
                    response.getEntity().getContent().readAllBytes(),
                    StandardCharsets.UTF_8
                );

                // 5. HTTP-level dispatch
                if (httpCode >= 500) {
                    log.warn("netgsm transient HTTP RETRY: code={}", httpCode);
                    return DeliveryAttemptResult.retry("HTTP " + httpCode, httpCode);
                }
                if (httpCode >= 400) {
                    log.warn("netgsm permanent HTTP FAIL: code={} body={}",
                        httpCode, abbrev(respBody));
                    return DeliveryAttemptResult.failed("HTTP " + httpCode, httpCode);
                }

                // 6. Provider response code parse (NetGSM REST v2 returns code in JSON)
                JsonNode node = objectMapper.readTree(respBody);
                String code = node.path("code").asText("");
                String desc = node.path("description").asText("unknown");
                String netgsmJobid = node.path("jobid").asText("");

                if ("00".equals(code) || code.isEmpty() && httpCode == 200) {
                    log.info("netgsm DELIVERED: jobid={} msg_id={}", netgsmJobid, providerMsgId);
                    return DeliveryAttemptResult.delivered(
                        netgsmJobid.isEmpty() ? providerMsgId : "netgsm-" + netgsmJobid);
                }
                // Permanent provider errors (no point retrying)
                if (isPermanentError(code)) {
                    log.warn("netgsm permanent provider FAIL: code={} desc={}", code, desc);
                    return DeliveryAttemptResult.failed("provider " + code + ": " + desc, httpCode);
                }
                // Unknown provider code → conservative RETRY
                log.warn("netgsm unknown provider code (RETRY): code={} desc={}", code, desc);
                return DeliveryAttemptResult.retry("provider " + code + ": " + desc, httpCode);
            });
        } catch (IOException e) {
            log.warn("netgsm IOException (treating as RETRY): {}", e.getMessage());
            return DeliveryAttemptResult.retry("io: " + e.getClass().getSimpleName(), null);
        }
    }

    /** Provider error codes considered permanent (no retry). */
    static boolean isPermanentError(String code) {
        if (code == null) return false;
        return switch (code) {
            case "20",  // message too long (segment limit broken)
                 "30",  // invalid credentials
                 "40",  // invalid msgheader
                 "50",  // invalid phone format
                 "70"   // IYS opt-out
                 -> true;
            default -> false;  // 60 (credit) and unknowns RETRY
        };
    }

    private static String basicAuth(String user, String pass) {
        String pair = user + ":" + pass;
        return "Basic " + java.util.Base64.getEncoder()
            .encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static CloseableHttpClient newClient() {
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .setResponseTimeout(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build();
        return HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 100 ? s.substring(0, 97) + "..." : s;
    }
}
