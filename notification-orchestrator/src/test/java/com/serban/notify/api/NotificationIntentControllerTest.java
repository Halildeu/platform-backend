package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc contract test — Codex 019df9ae Q6 AGREE (PR2'de OpenAPI surface dahil).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class NotificationIntentControllerTest extends AbstractPostgresTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NotificationTemplateRepository templateRepo;

    @BeforeEach
    void seedTemplate() {
        // Idempotent (Testcontainers reuse + DirtiesContext combo)
        if (templateRepo.findByTemplateIdAndVersionAndLocale("auth-password-reset", 1, "tr-TR")
                .isPresent()) {
            return;
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId("auth-password-reset");
        t.setVersion(1);
        t.setLocale("tr-TR");
        t.setSubject("Şifre");
        t.setBodyText("Hello ${user_name}");
        t.setActive(true);
        t.setCreatedBy("test");
        templateRepo.save(t);
    }

    @Test
    void postSubmitReturns202Accepted() throws Exception {
        SubmitIntentRequest req = newRequest(UUID.randomUUID().toString(), "ctl-key-1");
        mockMvc.perform(post("/api/v1/notify/intents")
                .header("X-Org-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.intentId").value(req.intentId()))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.trackingUrl").value("/api/v1/notify/intents/" + req.intentId()));
    }

    @Test
    void postSubmitMissingFieldReturns400() throws Exception {
        // Missing required intentId
        String invalidJson = "{\"orgId\":\"default\",\"topicKey\":\"x.y\"}";
        mockMvc.perform(post("/api/v1/notify/intents")
                .header("X-Org-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
            .andExpect(status().isBadRequest());
    }

    @Test
    void postSubmitCrossOrgReturns403() throws Exception {
        // Caller header X-Org-Id="org-a" but request.org_id="org-b" — Codex non-neg #1
        SubmitIntentRequest req = newRequest(UUID.randomUUID().toString(), "ctl-cross-key");
        mockMvc.perform(post("/api/v1/notify/intents")
                .header("X-Org-Id", "org-attacker")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden());
    }

    @Test
    void getStatusReturnsIntentForOwnerOrg() throws Exception {
        SubmitIntentRequest req = newRequest(UUID.randomUUID().toString(), "ctl-status-key");
        mockMvc.perform(post("/api/v1/notify/intents")
                .header("X-Org-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/notify/intents/{id}", req.intentId())
                .header("X-Org-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intentId").value(req.intentId()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.dispatchReason").value("DISPATCH_DISABLED"));
    }

    @Test
    void getStatusCrossOrgRejectedByGuard() throws Exception {
        // Faz 24 / PR-5.2 (Codex `019e0675`): the IntentController status
        // path is now guarded by NotifyOrgAccessGuard, which runs BEFORE
        // the repository's cross-tenant existence-disclosure 404. Under
        // the test profile Spring Boot seeds an anonymous Authentication
        // in the SecurityContext; the guard's default-org fallback
        // ("default") does not match the requested "org-attacker" so the
        // call legitimately 403s out before reaching the repo. Under the
        // production profile the same 403 path runs because the JWT
        // principal does not declare "org-attacker" — different code
        // path, same observable status.
        //
        // The original 404 contract (existence-disclosure for JWT-backed
        // callers reaching their OWN org-but-wrong-id) is now provided
        // by `findByIntentIdAndOrgId` returning empty inside the
        // repository — see the JWT-mock test below once added under
        // PR-5.5 strict cutover. For PR-5.2 we just assert the 403
        // gate fires before any repo lookup.
        SubmitIntentRequest req = newRequest(UUID.randomUUID().toString(), "ctl-leak-key");
        mockMvc.perform(post("/api/v1/notify/intents")
                .header("X-Org-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/notify/intents/{id}", req.intentId())
                .header("X-Org-Id", "org-attacker"))
            .andExpect(status().isForbidden());
    }

    private SubmitIntentRequest newRequest(String intentId, String idemKey) {
        return new SubmitIntentRequest(
            intentId,
            idemKey,
            "trace-" + intentId.substring(0, 8),
            "default",
            "auth.password-reset",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.subscriber,
                "1204", null, null, "Halil", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth-password-reset", null, "tr-TR"),
            List.of("email"),
            Map.of("user_name", "Halil"),
            null, null, null, null, null
        );
    }
}
