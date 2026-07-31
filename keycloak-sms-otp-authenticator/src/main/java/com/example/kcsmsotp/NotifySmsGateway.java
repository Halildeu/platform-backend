package com.example.kcsmsotp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The two-leg send chain measured in gitops#3212:
 *
 * <ol>
 *   <li><b>Mint</b> — {@code POST {auth}/oauth2/token}, Basic clientId:secret,
 *       form {@code grant_type=client_credentials&audience=notification-orchestrator&
 *       permissions=notify:intents:system} → {@code {"access_token": ...}}. Required
 *       because the notify internal path only honours {@code SVC_*} authorities the
 *       converter mints from an auth-service token's {@code perm} claim — a Keycloak
 *       client-credentials token cannot reach it.</li>
 *   <li><b>Intent</b> — {@code POST {notify}/api/v1/internal/notify/intents}, Bearer,
 *       camelCase JSON per SubmitIntentRequest, expect 202. Recipient is
 *       {@code {type: external, phone: E.164}}; the login user is not a notify
 *       subscriber.</li>
 * </ol>
 *
 * <p>No retries here: the user-visible "resend" button is the retry, with its own
 * ceiling in {@link SmsOtpCodeStore}.
 */
public class NotifySmsGateway {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final SmsOtpConfig cfg;

    public NotifySmsGateway(HttpClient http, ObjectMapper mapper, SmsOtpConfig cfg) {
        this.http = http;
        this.mapper = mapper;
        this.cfg = cfg;
    }

    /**
     * Mint a service token and submit the SMS intent.
     *
     * @param phone          E.164 recipient
     * @param localeTag      BCP-47 tag ("tr", "tr-TR", "en"...) — template resolver
     *                       falls back language-only, then en
     * @param code           the one-time code (payload for the template's vars.code)
     * @param idempotencyKey stable per send attempt (auth session id + resend count)
     */
    public void send(String phone, String localeTag, String code, String idempotencyKey)
            throws SmsSendException {
        String accessToken = mint();
        submitIntent(accessToken, phone, localeTag, code, idempotencyKey);
    }

    private String mint() throws SmsSendException {
        String basic = Base64.getEncoder().encodeToString(
                (cfg.clientId + ":" + cfg.secret).getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=client_credentials"
                + "&audience=" + URLEncoder.encode("notification-orchestrator", StandardCharsets.UTF_8)
                + "&permissions=" + URLEncoder.encode("notify:intents:system", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.tokenUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = exchange(request, "mint");
        if (response.statusCode() != 200) {
            throw new SmsSendException("mint failed: HTTP " + response.statusCode());
        }
        try {
            String token = mapper.readTree(response.body()).path("access_token").asText("");
            if (token.isBlank()) {
                throw new SmsSendException("mint failed: empty access_token");
            }
            return token;
        } catch (IOException e) {
            throw new SmsSendException("mint failed: unparseable token response", e);
        }
    }

    private void submitIntent(String accessToken, String phone, String localeTag,
            String code, String idempotencyKey) throws SmsSendException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intentId", UUID.randomUUID().toString());
        body.put("idempotencyKey", idempotencyKey);
        body.put("orgId", cfg.orgId);
        body.put("topicKey", cfg.topicKey);
        body.put("severity", "info");
        body.put("dataClassification", "security");
        body.put("recipients", List.of(Map.of(
                "type", "external",
                "phone", phone,
                "locale", localeTag)));
        body.put("template", Map.of(
                "templateId", cfg.templateId,
                "locale", localeTag));
        body.put("channels", List.of("sms"));
        body.put("payload", Map.of("code", code));

        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new SmsSendException("intent failed: body serialization", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.intentUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = exchange(request, "intent");
        if (response.statusCode() != 202) {
            throw new SmsSendException("intent failed: HTTP " + response.statusCode());
        }
    }

    private HttpResponse<String> exchange(HttpRequest request, String leg) throws SmsSendException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SmsSendException(leg + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsSendException(leg + " interrupted", e);
        }
    }
}
