package com.example.ethics.notification;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.model.NotificationOutbox;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpNotificationIntentGatewayTest {

    @Test
    void mintsDedicatedTokenAndSubmitsOnlyGenericIdempotentIntent() {
        var properties = new NotificationDeliveryProperties();
        properties.setEnabled(true);
        properties.setTokenUrl("http://auth.test/oauth2/token");
        properties.setOrchestratorBaseUrl("http://notify.test");
        properties.setClientId("ethics-service");
        properties.setClientSecret("synthetic-client-secret");
        properties.setRecipientSubscriberId("ethics-triage");
        properties.validate();

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString(
                "ethics-service:synthetic-client-secret"
                        .getBytes(StandardCharsets.UTF_8));
        server.expect(once(), requestTo("http://auth.test/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", expectedBasic))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "grant_type=client_credentials")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "audience=notification-orchestrator")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "permissions=notify%3Aintents%3Asystem")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"synthetic-access-token\",\"expires_in\":60}",
                        MediaType.APPLICATION_JSON));

        UUID deliveryId = UUID.randomUUID();
        server.expect(once(), requestTo("http://notify.test/api/v1/internal/notify/intents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer synthetic-access-token"))
                .andExpect(jsonPath("$.intentId").value("ethics-" + deliveryId))
                .andExpect(jsonPath("$.idempotencyKey").value("ethics-" + deliveryId))
                .andExpect(jsonPath("$.topicKey").value("ethics.case.activity"))
                .andExpect(jsonPath("$.recipients[0].subscriberId").value("ethics-triage"))
                .andExpect(jsonPath("$.payload").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.containsString("caseId"),
                                org.hamcrest.Matchers.containsString("reportId"),
                                org.hamcrest.Matchers.containsString("receipt"),
                                org.hamcrest.Matchers.containsString("narrative")))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        var gateway = new HttpNotificationIntentGateway(
                properties,
                new NotificationIntentPayloadFactory(properties),
                builder.build());
        gateway.submit(new NotificationOutbox(
                deliveryId,
                UUID.randomUUID(),
                NotificationOutboxPublisher.NEW_REPORT,
                Instant.parse("2026-07-24T00:00:00Z")));

        server.verify();
    }
}
