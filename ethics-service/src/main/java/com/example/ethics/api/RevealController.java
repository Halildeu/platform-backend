package com.example.ethics.api;

import com.example.ethics.api.RevealDtos.*;
import com.example.ethics.security.StaffContextResolver;
import com.example.ethics.service.RevealService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ethics/reveal-requests")
public class RevealController {
    private final RevealService service;
    private final StaffContextResolver context;

    public RevealController(RevealService service, StaffContextResolver context) {
        this.service = service;
        this.context = context;
    }

    @PostMapping
    ResponseEntity<RevealResponse> submit(@Valid @RequestBody RevealSubmitRequest body) {
        RevealResponse response = service.submit(context.required(), body);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping("/{id}/approvals")
    ResponseEntity<RevealResponse> approve(@PathVariable UUID id, @Valid @RequestBody RevealApproveRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.approve(context.required(), id, body.approverName(), body.approverRole()));
    }

    @PostMapping("/{id}/rejections")
    ResponseEntity<RevealResponse> reject(@PathVariable UUID id, @Valid @RequestBody RevealRejectRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.reject(context.required(), id, body.reason()));
    }

    @PostMapping("/{id}/executions")
    ResponseEntity<RevealPayloadResponse> execute(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.execute(context.required(), id));
    }

    @GetMapping
    ResponseEntity<List<RevealResponse>> listForCase(@RequestParam("case_id") UUID caseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listForCase(context.required(), caseId));
    }

    @GetMapping("/{id}/audit")
    ResponseEntity<List<RevealAuditEntry>> audit(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.audit(context.required(), id));
    }
}
