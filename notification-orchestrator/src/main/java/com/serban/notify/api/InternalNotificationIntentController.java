package com.serban.notify.api;

import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.authz.MfaDeliveryGrant;
import com.serban.notify.authz.MfaDeliveryGrantVerifier;
import com.serban.notify.api.dto.SubmitIntentResponse;
import com.serban.notify.service.IntentSubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal SYSTEM-submit endpoint (#734, Codex 019ef41c) for TRUSTED backend
 * services to submit a notification intent WITHOUT a per-user org-scoped JWT.
 *
 * <p>Use case: user-service emits an admin "new M365 user awaiting activation"
 * email on first auto-provision. user-service has no org-scoped user JWT, and
 * prod sets the strict flip {@code NOTIFY_SECURITY_DEFAULT_ORG_ID=""} so the
 * public {@code POST /api/v1/notify/intents} org-gate ({@link NotifyOrgAccessGuard})
 * can't be satisfied by a service caller.
 *
 * <p><strong>Trust model:</strong> a short-lived auth-service-minted SERVICE
 * token ({@code iss=auth-service}, {@code aud=notification-orchestrator},
 * {@code perm=["notify:intents:system"]}, iss=auth-service). The path is gated
 * in {@code SecurityConfig} by {@code hasAuthority("SVC_notify:intents:system")}
 * — the converter only emits {@code SVC_*} from the {@code perm} claim of a
 * service-issuer token, so a user token can never reach it; the
 * {@code @PreAuthorize} below is defense-in-depth. The org-access guard is
 * intentionally NOT invoked — authorization here is the service-principal
 * authority, not an org match — so the strict-flip does not affect it. The
 * body's {@code orgId} stays the partition/idempotency selector.
 *
 * <p>Delegates to the SAME {@link IntentSubmissionService#submit} as the public
 * controller (idempotency, abuse-guard, capacity, template resolution, persist
 * + audit all apply identically).
 */
@RestController
@RequestMapping("/api/v1/internal/notify/intents")
public class InternalNotificationIntentController {

    /** gitops#3212: header carrying the one-shot MFA delivery grant. */
    static final String GRANT_HEADER = "X-Mfa-Delivery-Grant";

    private final IntentSubmissionService submissionService;
    private final MfaDeliveryGrantVerifier grantVerifier;

    public InternalNotificationIntentController(IntentSubmissionService submissionService,
            MfaDeliveryGrantVerifier grantVerifier) {
        this.submissionService = submissionService;
        this.grantVerifier = grantVerifier;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SVC_notify:intents:system')")
    public ResponseEntity<SubmitIntentResponse> submitInternal(
            @Valid @RequestBody SubmitIntentRequest request,
            @RequestHeader(value = GRANT_HEADER, required = false) String mfaDeliveryGrant) {
        // gitops#3212: an MFA delivery grant, when present AND verified against
        // THIS request, authorises the recipient for this one delivery. An
        // absent, malformed, expired or mismatched grant simply yields null —
        // the delivery then takes the ordinary path and its full recipient
        // authz check. There is no request field that can turn the check off.
        MfaDeliveryGrant verified = grantVerifier.verify(mfaDeliveryGrant, request).orElse(null);
        SubmitIntentResponse response = submissionService.submit(request, verified);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
