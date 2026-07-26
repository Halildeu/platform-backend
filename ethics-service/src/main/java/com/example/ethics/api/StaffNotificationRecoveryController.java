package com.example.ethics.api;

import com.example.ethics.notification.NotificationDeadLetterService;
import com.example.ethics.security.StaffContextResolver;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Operator recovery for stranded new-work alerts.
 *
 * <p>Delivery can fail for reasons that have nothing to do with the signal
 * itself. When it does and the attempts run out, the alert used to be lost for
 * good — the report survived, but nothing would ever tell the ethics team it
 * existed. These two endpoints are the way back: read the backlog first, then
 * decide.
 */
@RestController
@RequestMapping("/api/v1/ethics/notifications")
@Validated
public class StaffNotificationRecoveryController {

    private final NotificationDeadLetterService deadLetters;
    private final StaffContextResolver context;

    public StaffNotificationRecoveryController(
            NotificationDeadLetterService deadLetters, StaffContextResolver context) {
        this.deadLetters = deadLetters;
        this.context = context;
    }

    /** How many alerts are stranded for this tenant, and since when. */
    @GetMapping("/dead-letters")
    ResponseEntity<NotificationDeadLetterService.DeadLetterSummary> summary() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deadLetters.summary(context.required()));
    }

    /**
     * Return stranded alerts to the queue, bounded per call.
     *
     * <p>Bounded on purpose: an unbounded requeue against a cause that still
     * stands turns a visible backlog into the same backlog plus a false belief
     * that it was handled.
     */
    @PostMapping("/dead-letters/requeue")
    ResponseEntity<RequeueResponse> requeue(
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        int moved = deadLetters.requeue(context.required(), limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RequeueResponse(moved, limit));
    }

    public record RequeueResponse(int requeued, int limit) {}
}
