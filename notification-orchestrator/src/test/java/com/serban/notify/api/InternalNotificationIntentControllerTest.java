package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.domain.NotificationIntent;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #734 (Codex 019ef41c): the internal SYSTEM-submit endpoint
 * ({@code POST /api/v1/internal/notify/intents}) reaches the SAME
 * {@code IntentSubmissionService.submit} as the public controller and accepts an
 * EXTERNAL recipient against the {@code auth.admin-invite} template (seeded by
 * V23, {@code external_allowed=true}) — with NO {@code X-Org-Id} header and no
 * org-gate (the org guard is not wired into the internal controller).
 *
 * <p>Security profile note: like {@link NotificationIntentControllerTest}, this
 * runs under {@code @ActiveProfiles("test")} where {@code SecurityConfig}
 * ({@code @Profile("!local & !test")}) is inactive — so this is the wiring /
 * happy-path contract test. The authority gate ({@code hasAuthority(
 * "notify:intents:system")} + the {@code perm}-claim mapping) is unit-tested in
 * {@code com.serban.notify.config.SecurityConfigConverterTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class InternalNotificationIntentControllerTest extends AbstractPostgresTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void internalSubmit_externalAdminRecipient_returns202() throws Exception {
        String intentId = UUID.randomUUID().toString();
        SubmitIntentRequest req = new SubmitIntentRequest(
            intentId,
            "user-activation:test-" + intentId.substring(0, 8),
            "trace-" + intentId.substring(0, 8),
            "platform-system",
            "auth.admin-invite",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of(new SubmitIntentRequest.RecipientRef(
                SubmitIntentRequest.RecipientRef.Type.external,
                null, "admin@example.com", null, "Admin", "tr-TR"
            )),
            new SubmitIntentRequest.TemplateRef("auth.admin-invite", null, "tr-TR"),
            List.of("email"),
            Map.of(
                "new_user_email", "newuser@serban.com.tr",
                "new_user_name", "New User",
                "user_id", "3"
            ),
            null, null, null, null, null
        );

        mockMvc.perform(post("/api/v1/internal/notify/intents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.intentId").value(intentId))
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }
}
