package com.serban.notify.api;

import com.serban.notify.api.dto.EmailSuppressionResponse;
import com.serban.notify.api.dto.EmailSuppressionUpsertRequest;
import com.serban.notify.domain.EmailSuppression;
import com.serban.notify.suppression.EmailSuppressionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin email suppression list management (Faz 23.8 M7 T4.3.b — Codex
 * `019e492f` AGREE).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/admin/notify/email-suppressions} — list
 *       active suppressions for the caller's org.</li>
 *   <li>{@code POST /api/v1/admin/notify/email-suppressions} — upsert
 *       a suppression entry (manual admin action; future DSN poll
 *       worker + webhook adapter call the same service path).</li>
 *   <li>{@code DELETE /api/v1/admin/notify/email-suppressions/{hash}}
 *       — manual release (user contacted support, alias change).</li>
 * </ul>
 *
 * <p>Auth contract mirrors {@link AdminDeliveryController}:
 * {@code audit-write} or {@code ROLE_ADMIN}. {@code X-Org-Id} header
 * required + reconciled with JWT.
 */
@RestController
@RequestMapping("/api/v1/admin/notify/email-suppressions")
@Tag(name = "notify-admin-email-suppression",
    description = "Email recipient suppression list (Faz 23.8 M7 T4.3.b)")
public class AdminEmailSuppressionController {

    private static final Logger log =
        LoggerFactory.getLogger(AdminEmailSuppressionController.class);

    private final EmailSuppressionService service;

    public AdminEmailSuppressionController(EmailSuppressionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('audit-read','audit-write','ROLE_ADMIN')")
    @Operation(summary = "List email suppressions for the caller's org")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Suppression rows"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Insufficient authority")
    })
    public ResponseEntity<List<EmailSuppressionResponse>> list(
        @RequestHeader("X-Org-Id") String orgId
    ) {
        List<EmailSuppressionResponse> items = service.listForOrg(orgId).stream()
            .map(EmailSuppressionResponse::fromEntity)
            .toList();
        return ResponseEntity.ok(items);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('audit-write','ROLE_ADMIN')")
    @Operation(summary = "Upsert an email suppression entry (manual admin or future DSN/webhook ingest)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Upsert result"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Insufficient authority")
    })
    public ResponseEntity<EmailSuppressionResponse> upsert(
        @RequestHeader("X-Org-Id") String orgId,
        @RequestHeader(value = "X-Actor", required = false) String actor,
        @RequestBody @Valid EmailSuppressionUpsertRequest request
    ) {
        EmailSuppressionService.UpsertInput input = new EmailSuppressionService.UpsertInput(
            orgId,
            request.recipientHash(),
            request.recipientType() != null
                ? request.recipientType()
                : EmailSuppression.RecipientType.EXTERNAL,
            request.reason(),
            EmailSuppression.Source.MANUAL_API,
            request.provider(),
            null,
            request.summaryRedacted(),
            null,
            actor
        );
        EmailSuppression row = service.upsert(input);
        log.info("admin email suppression upsert org={} reason={} actor={}",
            orgId, request.reason(), actor);
        return ResponseEntity.ok(EmailSuppressionResponse.fromEntity(row));
    }

    @DeleteMapping("/{recipientHash}")
    @PreAuthorize("hasAnyAuthority('audit-write','ROLE_ADMIN')")
    @Operation(summary = "Release (delete) an email suppression entry")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Released"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Insufficient authority"),
        @ApiResponse(responseCode = "404", description = "No matching row")
    })
    public ResponseEntity<Void> release(
        @RequestHeader("X-Org-Id") String orgId,
        @PathVariable String recipientHash
    ) {
        boolean removed = service.release(orgId, recipientHash);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        log.info("admin email suppression released org={}", orgId);
        return ResponseEntity.noContent().build();
    }
}
