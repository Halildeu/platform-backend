package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.ethics.api.EthicsDtos.ReportMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Faz 35 — the boundary the ES-2 pilot is allowed to run inside.
 *
 * <p>The pilot's privacy evidence rests on a fact about the data, not on a control:
 * <b>no reporter identity is collected at all</b>. The backup and restore drill
 * (ES-209, gitops#2661) could show that a restored copy contains no identity only
 * because there is none to contain — every one of the 164 live reports is
 * {@code ANONYMOUS}, and there is no identity table in the schema.
 *
 * <p>That is a narrower guarantee than the one the acceptance criteria describe, which
 * assume a Case ↔ Identity ↔ Link separation and an operator who cannot read two
 * compartments at once. Building that separation is gitops#2949; until it exists and
 * has produced its own restore, join and operator-separation evidence, turning on
 * {@code CONFIDENTIAL} or {@code NAMED} would put identity into a system whose privacy
 * story was measured without it.
 *
 * <p>So the boundary is enforced here rather than written down. Enabling another mode
 * is a one-line change in {@code EthicsService}; this test is what makes that line
 * fail loudly, at the moment it is written, instead of quietly widening what the pilot
 * covers. Delete it when gitops#2949 lands — not before, and not to make a build green.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnonymousOnlyPilotBoundaryTest {

    private static final String SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean com.example.ethics.security.EthicsAuthorization authorization;
    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier entitlements;

    @BeforeEach
    void allow() {
        when(authorization.can(any(), anyString(), any())).thenReturn(true);
        when(authorization.gateFor(any(), anyString())).thenReturn(
                new com.example.ethics.security.EthicsAuthorization.CaseGate(true, java.util.Set.of()));
        when(entitlements.hasManageEntitlement(anyString())).thenReturn(true);
    }

    private String payload(ReportMode mode, String key) {
        return "{\"mode\":\"" + mode + "\",\"category\":\"OTHER\",\"subject\":\"Sinir testi\","
                + "\"description\":\"Sentetik\",\"locale\":\"tr\",\"accessSecret\":\"" + SECRET
                + "\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
    }

    /**
     * Every mode other than {@code ANONYMOUS} is refused — asserted over the whole enum
     * rather than over the two that exist today, so a fourth mode added later is caught
     * as well.
     */
    @Test
    @DisplayName("anonim olmayan her ihbar modu reddedilir — kimlik kasası (gitops#2949) gelene kadar")
    void everyNonAnonymousModeIsRefused() throws Exception {
        for (ReportMode mode : ReportMode.values()) {
            if (mode == ReportMode.ANONYMOUS) continue;
            mvc.perform(post("/api/v1/public/ethics/reports")
                            .header("Host", "etik.acik.com")
                            .header("Idempotency-Key", "boundary-" + mode)
                            .contentType(MediaType.APPLICATION_JSON).content(payload(mode, "b")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("REPORT_MODE_NOT_ENABLED"));
        }
    }

    /**
     * The refusal has to happen before anything is written. A mode that is rejected at
     * the end of intake would still have left a row behind, and the pilot's "no identity
     * is collected" claim is about storage, not about response codes.
     */
    @Test
    @DisplayName("reddedilen mod hiçbir satır bırakmaz")
    void arefusedModeStoresNothing() throws Exception {
        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com")
                        .header("Idempotency-Key", "boundary-no-write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(ReportMode.NAMED, "c")))
                .andExpect(status().isUnprocessableEntity());

        // Replaying the same key must behave as a first attempt: nothing was recorded,
        // so there is no idempotency entry to replay.
        mvc.perform(post("/api/v1/public/ethics/reports")
                        .header("Host", "etik.acik.com")
                        .header("Idempotency-Key", "boundary-no-write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(ReportMode.NAMED, "c")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.idempotentReplay").doesNotExist());
    }

    /**
     * A reminder in the vocabulary itself. {@code CONFIDENTIAL} and {@code NAMED} are
     * declared because the product will have them; their presence in the enum is not
     * permission to accept them, and this asserts the two facts stay separate.
     */
    @Test
    @DisplayName("sözlükte tanımlı olmak kabul edilmek demek değildir")
    void beingInTheVocabularyIsNotBeingAccepted() {
        assertThat(Arrays.asList(ReportMode.values()))
                .as("ileride kimlikli modlar gelecek; bu test onları engellemiyor, "
                        + "kimlik kasası kanıtı olmadan açılmalarını engelliyor")
                .contains(ReportMode.CONFIDENTIAL, ReportMode.NAMED);
    }
}
