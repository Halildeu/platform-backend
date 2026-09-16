package com.serban.notify.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Flow;
import static com.serban.notify.push.NativePushHttpSender.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativePushHttpSenderTest {
    private final HttpClient http = mock(HttpClient.class);
    private final NativePushHttpSender.MeetingEvent event = new NativePushHttpSender.MeetingEvent(
        UUID.randomUUID(), UUID.randomUUID(), "meeting.summary.ready");
    private HttpRequest sent;

    @SuppressWarnings("unchecked")
    private void response(int code, String body) throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            sent = call.getArgument(0);
            return response;
        });
    }
    private NativePushHttpSender sender(String provider, boolean sandbox) {
        return new NativePushHttpSender(http, new ObjectMapper(), provider, "example-app", sandbox, () -> "synthetic-auth");
    }

    @Test void fcmUsesV1AndGenericPayloadAndReportsAcceptanceNotDelivery() throws Exception {
        response(200, "{\"name\":\"projects/example-app/messages/id\"}");
        assertEquals(PROVIDER_ACCEPTED, sender("FCM", false).send("synthetic-token", event).outcome());
        assertEquals("https://fcm.googleapis.com/v1/projects/example-app/messages:send", sent.uri().toString());
        assertEquals("Bearer synthetic-auth", sent.headers().firstValue("Authorization").orElseThrow());
        var json = new ObjectMapper().readTree(body(sent));
        assertEquals(event.meetingId().toString(), json.at("/message/data/meetingId").asText());
        assertEquals("meeting.summary.ready", json.at("/message/data/eventType").asText());
        assertFalse(json.at("/message/data").has("transcript"));
    }

    @Test void apnsUsesExplicitSigningEnvironmentAndHeaders() throws Exception {
        response(200, "");
        assertEquals(PROVIDER_ACCEPTED, sender("APNS", true).send("abcdef012345", event).outcome());
        assertEquals("api.sandbox.push.apple.com", sent.uri().getHost());
        assertEquals(HttpClient.Version.HTTP_2, sent.version().orElseThrow());
        assertEquals("alert", sent.headers().firstValue("apns-push-type").orElseThrow());
        assertEquals("example-app", sent.headers().firstValue("apns-topic").orElseThrow());
        assertEquals(event.eventId().toString(), sent.headers().firstValue("apns-id").orElseThrow());
        assertEquals(event.meetingId().toString(), new ObjectMapper().readTree(body(sent)).path("meetingId").asText());
    }

    @Test void onlyExplicitUnregisteredResponseInvalidatesFcmToken() throws Exception {
        response(404, "{}");
        assertEquals(REJECTED, sender("FCM", false).send("synthetic-token", event).outcome());
        response(404, "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}");
        assertEquals(INVALID_TOKEN, sender("FCM", false).send("synthetic-token", event).outcome());
    }

    @Test void onlyExplicitUnregisteredResponseInvalidatesApnsToken() throws Exception {
        response(410, "{\"reason\":\"Unregistered\"}");
        assertEquals(INVALID_TOKEN, sender("APNS", false).send("abcdef", event).outcome());
        response(400, "{\"reason\":\"BadDeviceToken\"}");
        assertEquals(REJECTED, sender("APNS", false).send("abcdef", event).outcome());
    }

    @Test void retryAndCredentialFailuresDoNotDeleteTokens() throws Exception {
        for (int status : new int[] {429, 500, 503}) {
            response(status, "{}");
            assertEquals(RETRY, sender("FCM", false).send("synthetic-token", event).outcome());
        }
        for (int status : new int[] {401, 403}) {
            response(status, "{}");
            assertEquals(CONFIGURATION_ERROR, sender("FCM", false).send("synthetic-token", event).outcome());
        }
    }

    @Test void malformedSuccessIsNotAccepted() throws Exception {
        response(200, "{}");
        assertEquals(RETRY, sender("FCM", false).send("synthetic-token", event).outcome());
        response(200, "broken-json");
        assertEquals(RETRY, sender("FCM", false).send("synthetic-token", event).outcome());
        response(200, "   ");
        assertEquals(RETRY, sender("FCM", false).send("synthetic-token", event).outcome());
    }

    @Test void invalidTokenOrMissingCredentialNeverSends() {
        assertEquals(REJECTED, sender("APNS", false).send("../../other", event).outcome());
        var missing = new NativePushHttpSender(http, new ObjectMapper(), "FCM", "example-app", false, () -> "");
        assertEquals(CONFIGURATION_ERROR, missing.send("synthetic-token", event).outcome());
        verifyNoInteractions(http);
    }

    @Test void transportFailureDoesNotExposeTokenOrCredential() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("synthetic-token synthetic-auth"));
        var result = sender("FCM", false).send("synthetic-token", event);
        assertEquals(RETRY, result.outcome());
        assertFalse(result.toString().contains("synthetic"));
    }

    private static byte[] body(HttpRequest request) {
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk); }
            public void onError(Throwable failure) { throw new AssertionError(failure); }
            public void onComplete() {}
        });
        return bytes.toByteArray();
    }
}
