package com.serban.notify.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * NetGSM DLR (Delivery Receipt) callback payload (Faz 23.4 PR-F).
 *
 * <p>NetGSM posts JSON to our configured webhook URL after each SMS
 * delivery attempt. Payload shape (subset; we ignore unused fields):
 * <pre>
 * {
 *   "jobid": "abc-12345",
 *   "code": "00",
 *   "no": "905321234567",
 *   "delivered_at": "2026-05-07T10:15:30Z"
 * }
 * </pre>
 *
 * <p>Status code semantics (NetGSM REST v2 DLR codes):
 * <ul>
 *   <li>{@code 00} — DELIVERED (terminal success)</li>
 *   <li>{@code 04} — REJECTED by carrier (FAILED terminal)</li>
 *   <li>{@code 05} — UNDELIVERABLE (FAILED terminal)</li>
 *   <li>{@code 16} — EXPIRED in carrier queue (FAILED terminal)</li>
 *   <li>{@code 17} — REJECTED by IYS opt-out (FAILED terminal — KVKK)</li>
 *   <li>{@code 70} — Same as 17 (provider variance)</li>
 *   <li>others — RETRY-able transient (acked but not terminal)</li>
 * </ul>
 *
 * <p>JsonProperty mapping uses snake_case to match NetGSM contract.
 */
public record NetgsmDlrRequest(

    @JsonProperty("jobid")
    @NotBlank(message = "jobid required")
    @Size(max = 128)
    String jobid,

    @JsonProperty("code")
    @NotBlank(message = "code required")
    @Size(max = 8)
    String code,

    /** Recipient phone (E.164 with or without leading +). Logged as audit hash only. */
    @JsonProperty("no")
    @Size(max = 32)
    String phone,

    /** ISO-8601 delivery timestamp (provider clock); optional. */
    @JsonProperty("delivered_at")
    @Size(max = 64)
    String deliveredAt,

    /**
     * Optional human-readable description from provider. NetGSM may send
     * up to ~800 chars (carrier reject reason chains); generous limit
     * prevents 400 reject on legit DLR.
     */
    @JsonProperty("description")
    @Size(max = 1024)
    String description
) {}
