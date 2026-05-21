package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.api.dto.PushEndpointListResponse;
import com.serban.notify.api.dto.PushSubscribeRequest;
import com.serban.notify.api.dto.PushSubscribeResponse;
import com.serban.notify.push.PushSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PushSubscriptionController} @WebMvcTest slice
 * (Faz 23.7 M7 T4.2 PR-W3 iter-2 — Codex {@code 019e4a57} P4 absorb).
 *
 * <p>Test scope:
 * <ul>
 *   <li>POST happy path — returns endpoint UUID + status</li>
 *   <li>POST validation: invalid URL scheme (http://) → 400</li>
 *   <li>GET /me — JSON shape PII boundary (raw URL/keys YOK)</li>
 *   <li>DELETE — 200 OK for both deleted and no_op</li>
 *   <li>Headers binding (X-Org-Id + X-Subscriber-Id)</li>
 * </ul>
 *
 * <p>Identity guard 403 paths broader SecurityConfig IT scope; bu slice
 * controller-layer contract'a odaklanır ({@code addFilters = false}).
 */
@WebMvcTest(controllers = PushSubscriptionController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class PushSubscriptionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PushSubscriptionService service;
    @MockBean SubscriberIdentityGuard subscriberIdentityGuard;
    @MockBean NotifyOrgAccessGuard notifyOrgAccessGuard;
    @MockBean JwtDecoder jwtDecoder;

    /** Valid uncompressed P-256 public key (65 bytes; first byte 0x04). */
    private static final String VALID_P256DH;
    private static final String VALID_AUTH_SECRET;

    static {
        byte[] pub = new byte[65];
        pub[0] = 0x04;
        for (int i = 1; i < 65; i++) pub[i] = (byte) (i & 0xFF);
        VALID_P256DH = Base64.getUrlEncoder().withoutPadding().encodeToString(pub);
        byte[] auth = new byte[16];
        for (int i = 0; i < 16; i++) auth[i] = (byte) (0x10 + i);
        VALID_AUTH_SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(auth);
    }

    @Test
    void subscribePostReturns200WithEndpointId() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.subscribe(eq("acme"), eq("1204"), any(PushSubscribeRequest.class)))
            .thenReturn(new PushSubscribeResponse(id, "created"));
        doNothing().when(notifyOrgAccessGuard).requireOrgAccessOrThrow(anyString());
        doNothing().when(subscriberIdentityGuard).requireMatchOrThrow(anyString());

        PushSubscribeRequest req = new PushSubscribeRequest(
            "https://fcm.googleapis.com/fcm/send/token",
            VALID_P256DH, VALID_AUTH_SECRET, "Mozilla/5.0"
        );

        mockMvc.perform(post("/api/v1/notify/push/subscribe")
                .header("X-Org-Id", "acme")
                .header("X-Subscriber-Id", "1204")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpointId").value(id.toString()))
            .andExpect(jsonPath("$.status").value("created"));
    }

    @Test
    void subscribePostRejectsHttpSchemeWith400() throws Exception {
        // DTO @Pattern enforces ^https://.+ at controller layer; no service call
        doNothing().when(notifyOrgAccessGuard).requireOrgAccessOrThrow(anyString());
        doNothing().when(subscriberIdentityGuard).requireMatchOrThrow(anyString());

        PushSubscribeRequest req = new PushSubscribeRequest(
            "http://insecure.example/push",
            VALID_P256DH, VALID_AUTH_SECRET, null
        );

        mockMvc.perform(post("/api/v1/notify/push/subscribe")
                .header("X-Org-Id", "acme")
                .header("X-Subscriber-Id", "1204")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void subscribePostMissingHeaderReturns400() throws Exception {
        PushSubscribeRequest req = new PushSubscribeRequest(
            "https://fcm.googleapis.com/fcm/send/token",
            VALID_P256DH, VALID_AUTH_SECRET, null
        );

        // Missing X-Subscriber-Id header
        mockMvc.perform(post("/api/v1/notify/push/subscribe")
                .header("X-Org-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listMineReturnsMinimalProjectionWithoutRawUrlOrKeys() throws Exception {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(service.listActive(eq("acme"), eq("1204")))
            .thenReturn(new PushEndpointListResponse(List.of(
                new PushEndpointListResponse.Endpoint(
                    id, "Mozilla/5.0 Chrome", "Chrome",
                    now.minusDays(2), now.minusMinutes(5)
                )
            )));
        doNothing().when(notifyOrgAccessGuard).requireOrgAccessOrThrow(anyString());
        doNothing().when(subscriberIdentityGuard).requireMatchOrThrow(anyString());

        var result = mockMvc.perform(get("/api/v1/notify/push/subscribe/me")
                .header("X-Org-Id", "acme")
                .header("X-Subscriber-Id", "1204"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpoints.length()").value(1))
            .andExpect(jsonPath("$.endpoints[0].endpointId").value(id.toString()))
            .andExpect(jsonPath("$.endpoints[0].userAgent").value("Mozilla/5.0 Chrome"))
            .andExpect(jsonPath("$.endpoints[0].platformHint").value("Chrome"))
            .andReturn();

        // PII boundary verify (Codex 019e49e7 P8 / 019e4a57 #1): raw endpoint
        // URL, p256dhKey, authSecret response'da YOK
        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
            .doesNotContain("endpointUrl")
            .doesNotContain("p256dhKey")
            .doesNotContain("authSecret");
    }

    @Test
    void deleteReturns200Deleted() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.unsubscribe(eq("acme"), eq("1204"), eq(id))).thenReturn(true);
        doNothing().when(notifyOrgAccessGuard).requireOrgAccessOrThrow(anyString());
        doNothing().when(subscriberIdentityGuard).requireMatchOrThrow(anyString());

        mockMvc.perform(delete("/api/v1/notify/push/subscribe/{id}", id)
                .header("X-Org-Id", "acme")
                .header("X-Subscriber-Id", "1204"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpoint_id").value(id.toString()))
            .andExpect(jsonPath("$.status").value("deleted"));
    }

    @Test
    void deleteReturns200NoOpWhenNotOwnedOrAlreadyDeleted() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.unsubscribe(eq("acme"), eq("1204"), eq(id))).thenReturn(false);
        doNothing().when(notifyOrgAccessGuard).requireOrgAccessOrThrow(anyString());
        doNothing().when(subscriberIdentityGuard).requireMatchOrThrow(anyString());

        // Codex 019e4a57 P3 absorb: cross-subscriber + already-deleted +
        // not-found hepsi 200 no_op (endpoint enumeration koruması).
        mockMvc.perform(delete("/api/v1/notify/push/subscribe/{id}", id)
                .header("X-Org-Id", "acme")
                .header("X-Subscriber-Id", "1204"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpoint_id").value(id.toString()))
            .andExpect(jsonPath("$.status").value("no_op"));
    }
}
