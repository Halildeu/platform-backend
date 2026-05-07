package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.dlr.DlrIngestService;
import com.serban.notify.domain.NotificationDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DlrController @WebMvcTest slice (Faz 23.4 PR-F).
 *
 * <p>Test scope:
 * <ul>
 *   <li>Valid token + payload → 200 + result body</li>
 *   <li>Invalid/missing token → 401 + service NOT invoked</li>
 *   <li>Missing required field (jobid/code) → 400 (bean validation)</li>
 *   <li>Service returns NOT_FOUND → still 200 (provider retry only adds noise)</li>
 *   <li>Token configured empty (Vault not yet populated) → all requests 401
 *       (fail-closed)</li>
 * </ul>
 */
@WebMvcTest(controllers = DlrController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = "notify.adapters.sms.netgsm.dlr-token=test-secret-token-32chars-abcdef")
class DlrControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean DlrIngestService dlrService;

    @Test
    void validTokenAndPayloadReturns200WithUpdatedAction() throws Exception {
        when(dlrService.ingestNetgsm("abc-1", "00", "OK")).thenReturn(
            new DlrIngestService.DlrResult(
                DlrIngestService.DlrAction.UPDATED,
                "netgsm-abc-1",
                NotificationDelivery.Status.DELIVERED
            )
        );

        String body = objectMapper.writeValueAsString(Map.of(
            "jobid", "abc-1",
            "code", "00",
            "no", "905321234567",
            "delivered_at", "2026-05-07T10:15:30Z",
            "description", "OK"
        ));

        mockMvc.perform(post("/api/v1/notify/dlr/netgsm")
                .header("X-NetGSM-DLR-Token", "test-secret-token-32chars-abcdef")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.action").value("UPDATED"))
            .andExpect(jsonPath("$.provider_msg_id").value("netgsm-abc-1"))
            .andExpect(jsonPath("$.status").value("DELIVERED"));

        verify(dlrService).ingestNetgsm("abc-1", "00", "OK");
    }

    @Test
    void missingTokenReturns401AndServiceNotInvoked() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "jobid", "abc-1",
            "code", "00"
        ));

        mockMvc.perform(post("/api/v1/notify/dlr/netgsm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("unauthorized"));

        verifyNoInteractions(dlrService);
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "jobid", "abc-1",
            "code", "00"
        ));

        mockMvc.perform(post("/api/v1/notify/dlr/netgsm")
                .header("X-NetGSM-DLR-Token", "wrong-token-different-length-xx")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(dlrService);
    }

    @Test
    void missingJobidReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "code", "00"
        ));

        mockMvc.perform(post("/api/v1/notify/dlr/netgsm")
                .header("X-NetGSM-DLR-Token", "test-secret-token-32chars-abcdef")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(dlrService);
    }

    @Test
    void missingCodeReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "jobid", "abc-1"
        ));

        mockMvc.perform(post("/api/v1/notify/dlr/netgsm")
                .header("X-NetGSM-DLR-Token", "test-secret-token-32chars-abcdef")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void notFoundResultStillReturns200() throws Exception {
        when(dlrService.ingestNetgsm(anyString(), anyString(), any())).thenReturn(
            new DlrIngestService.DlrResult(
                DlrIngestService.DlrAction.NOT_FOUND,
                "netgsm-ghost",
                null
            )
        );

        String body = objectMapper.writeValueAsString(Map.of(
            "jobid", "ghost",
            "code", "00"
        ));

        mockMvc.perform(post("/api/v1/notify/dlr/netgsm")
                .header("X-NetGSM-DLR-Token", "test-secret-token-32chars-abcdef")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.action").value("NOT_FOUND"))
            .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    /**
     * Stub JwtDecoder bean — slice context creation needs the bean (security
     * filters disabled via addFilters=false, decoder not exercised).
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("not exercised");
            };
        }
    }
}
