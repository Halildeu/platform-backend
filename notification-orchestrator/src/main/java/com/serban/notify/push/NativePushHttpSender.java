package com.serban.notify.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Transport only. No Spring bean until credential refresh and delivery routing are wired. */
public final class NativePushHttpSender {
    public enum Outcome { PROVIDER_ACCEPTED, INVALID_TOKEN, RETRY, REJECTED, CONFIGURATION_ERROR }
    public record Result(Outcome outcome, Integer httpStatus) {}
    public record MeetingEvent(UUID eventId, UUID meetingId, String eventType) {
        public MeetingEvent {
            if (eventId == null || meetingId == null || eventType == null || !Set.of("meeting.summary.ready",
                "meeting.action.assigned", "meeting.transcript.ready").contains(eventType))
                throw new IllegalArgumentException("Unsupported native meeting event");
        }
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Supplier<String> authorization;
    private final String provider;
    private final String application;
    private final URI endpoint;

    // Authorization supplier must refresh short-lived credentials; never a device token.
    public static NativePushHttpSender fcm(String projectId, Supplier<String> accessToken) {
        return new NativePushHttpSender(client(), new ObjectMapper(), "FCM", projectId, false, accessToken);
    }
    // APNs sandbox is the signing entitlement, NOT inferred from TEST/PRODUCTION business scope.
    public static NativePushHttpSender apns(String topic, boolean sandbox, Supplier<String> providerJwt) {
        return new NativePushHttpSender(client(), new ObjectMapper(), "APNS", topic, sandbox, providerJwt);
    }
    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }
    NativePushHttpSender(HttpClient http, ObjectMapper mapper, String provider, String application,
                         boolean sandbox, Supplier<String> authorization) {
        if (!Set.of("FCM", "APNS").contains(provider) || application == null
            || !application.matches("[A-Za-z0-9_.-]{1,255}"))
            throw new IllegalArgumentException("Invalid native provider configuration");
        this.http = http;
        this.mapper = mapper;
        this.provider = provider;
        this.application = application;
        this.authorization = authorization;
        this.endpoint = URI.create(provider.equals("FCM")
            ? "https://fcm.googleapis.com/v1/projects/" + application + "/messages:send"
            : (sandbox ? "https://api.sandbox.push.apple.com/3/device/" : "https://api.push.apple.com/3/device/"));
    }

    public Result send(String token, MeetingEvent event) {
        if (event == null || token == null || !token.matches(provider.equals("APNS") ? "[A-Fa-f0-9]{2,4096}" : "[A-Za-z0-9_:.-]{1,4096}"))
            return new Result(Outcome.REJECTED, null);
        String bearer;
        try { bearer = authorization.get(); }
        catch (RuntimeException failure) { return new Result(Outcome.CONFIGURATION_ERROR, null); }
        if (bearer == null || bearer.isBlank() || bearer.indexOf('\r') >= 0 || bearer.indexOf('\n') >= 0)
            return new Result(Outcome.CONFIGURATION_ERROR, null);
        try {
            Map<String, String> data = Map.of("eventId", event.eventId().toString(),
                "meetingId", event.meetingId().toString(), "eventType", event.eventType());
            Map<String, String> alert = Map.of("title", "Toplantı güncellemesi", "body", "Toplantı sonucunu uygulamada görüntüleyebilirsiniz.");
            Object payload = provider.equals("FCM")
                ? Map.of("message", Map.of("token", token, "notification", alert, "data", data))
                : Map.of("aps", Map.of("alert", alert), "eventId", data.get("eventId"),
                    "meetingId", data.get("meetingId"), "eventType", data.get("eventType"));
            byte[] body = mapper.writeValueAsBytes(payload);
            if (body.length > 4096) return new Result(Outcome.REJECTED, null);
            var request = HttpRequest.newBuilder(provider.equals("FCM") ? endpoint : URI.create(endpoint + token))
                .version(HttpClient.Version.HTTP_2).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json");
            if (provider.equals("APNS")) request.header("apns-topic", application)
                .header("apns-push-type", "alert").header("apns-priority", "10")
                .header("apns-id", event.eventId().toString());
            // Bounded read also limits malformed provider responses. Never return/log raw bodies.
            var response = http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (var stream = response.body()) { responseBody = stream.readNBytes(16385); }
            if (responseBody.length > 16384) return new Result(Outcome.RETRY, response.statusCode());
            return classify(response.statusCode(), responseBody);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRY, null);
        } catch (IOException failure) {
            return new Result(Outcome.RETRY, null);
        }
    }

    private Result classify(int status, byte[] body) {
        if (status == 429 || status >= 500) return new Result(Outcome.RETRY, status);
        if (status == 401 || status == 403) return new Result(Outcome.CONFIGURATION_ERROR, status);
        JsonNode json;
        try { json = body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body); }
        catch (IOException failure) { return new Result(status == 200 ? Outcome.RETRY : Outcome.REJECTED, status); }
        if (json == null || !json.isObject()) return new Result(status == 200 ? Outcome.RETRY : Outcome.REJECTED, status);
        if (status == 200) {
            if (provider.equals("FCM") && !json.path("name").asText().startsWith("projects/" + application + "/messages/"))
                return new Result(Outcome.RETRY, status);
            return new Result(Outcome.PROVIDER_ACCEPTED, status);
        }
        if (provider.equals("APNS") && status == 410 && "Unregistered".equals(json.path("reason").asText()))
            return new Result(Outcome.INVALID_TOKEN, status);
        if (provider.equals("FCM") && status == 404) {
            for (JsonNode detail : json.path("error").path("details")) {
                if ("type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(detail.path("@type").asText())
                    && "UNREGISTERED".equals(detail.path("errorCode").asText()))
                    return new Result(Outcome.INVALID_TOKEN, status);
            }
        }
        return new Result(Outcome.REJECTED, status);
    }
}
