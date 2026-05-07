package com.serban.notify.api;

import com.serban.notify.api.dto.NetgsmDlrRequest;
import com.serban.notify.dlr.DlrIngestService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.util.Map;

/**
 * DLR (Delivery Receipt) callback controller — Faz 23.4 PR-F.
 *
 * <p>Provider webhook endpoint: receives terminal delivery status from
 * NetGSM after SMS dispatch. Public path (NetGSM posts from Internet)
 * authenticated via shared-secret header token (constant-time compare).
 *
 * <p>Auth model — shared secret header {@code X-NetGSM-DLR-Token}:
 * <ul>
 *   <li>Production: token from Vault {@code kv/platform/notify/sms/netgsm/dlr-token}</li>
 *   <li>Test/dev: empty default → adapter fail-closed (token mismatch → 401)</li>
 *   <li>Constant-time {@code MessageDigest.isEqual} prevents timing oracle</li>
 * </ul>
 *
 * <p>Why shared-secret token (not HMAC)? NetGSM REST v2 webhook does NOT
 * sign DLR callbacks. Shared-secret token in header is the standard
 * pattern provider supports (configured in NetGSM admin UI as part of
 * the webhook URL).
 *
 * <p>Response codes:
 * <ul>
 *   <li>200 — DLR processed (UPDATED, NOOP, or NOT_FOUND); body
 *       includes action + provider_msg_id</li>
 *   <li>400 — payload validation (missing jobid / code)</li>
 *   <li>401 — invalid or missing shared-secret token</li>
 *   <li>500 — server error (provider should retry)</li>
 * </ul>
 *
 * <p>Idempotency: provider may re-post same DLR; service-level no-op
 * handling prevents double-mutation. Always returns 200 even on NOT_FOUND
 * (provider retry only adds noise; we log + audit silently).
 */
@RestController
@RequestMapping("/api/v1/notify/dlr")
public class DlrController {

    private static final Logger log = LoggerFactory.getLogger(DlrController.class);

    private final DlrIngestService dlrService;

    @Value("${notify.adapters.sms.netgsm.dlr-token:}")
    private String netgsmDlrToken;

    public DlrController(DlrIngestService dlrService) {
        this.dlrService = dlrService;
    }

    /**
     * NetGSM DLR webhook endpoint.
     */
    @PostMapping(path = "/netgsm")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "DLR processed (action in body)"),
        @ApiResponse(responseCode = "400", description = "Payload validation failed"),
        @ApiResponse(responseCode = "401", description = "Invalid shared-secret token")
    })
    public ResponseEntity<Map<String, Object>> netgsmDlr(
        @RequestHeader(name = "X-NetGSM-DLR-Token", required = false) String token,
        @Valid @RequestBody NetgsmDlrRequest request
    ) {
        // Constant-time token comparison (prevents timing oracle)
        if (!isValidToken(token)) {
            log.warn("dlr netgsm: invalid/missing token (jobid={})", request.jobid());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "unauthorized", "message", "invalid dlr token"));
        }

        // Faz 23.4 PR-F Codex iter-1 P2.1 absorb: pass provider's
        // delivered_at (ISO-8601). Service falls back to NOW() on
        // null/blank/malformed.
        DlrIngestService.DlrResult result = dlrService.ingestNetgsm(
            request.jobid(),
            request.code(),
            request.description(),
            request.deliveredAt()
        );

        return ResponseEntity.ok(Map.of(
            "action", result.action().name(),
            "provider_msg_id", result.providerMsgId(),
            "status", result.currentStatus() != null ? result.currentStatus().name() : "UNKNOWN"
        ));
    }

    /**
     * Constant-time token comparison. Returns false when configured token is
     * empty (fail-closed — production cannot accept DLRs without token configured).
     */
    private boolean isValidToken(String provided) {
        if (netgsmDlrToken == null || netgsmDlrToken.isBlank()) {
            // Fail-closed: misconfigured (Vault path missing) → reject all
            return false;
        }
        if (provided == null) return false;
        // MessageDigest.isEqual is constant-time per Java contract
        byte[] a = netgsmDlrToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (a.length != b.length) {
            // Length mismatch — still do the compare to avoid length-leak side channel
            // (use safe sentinel arrays of equal length)
            byte[] pad = new byte[a.length];
            return MessageDigest.isEqual(a, pad) && false;
        }
        return MessageDigest.isEqual(a, b);
    }
}
