package com.example.meeting.dto.v1.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Göreve ata" people-picker search — Faz 24 (gitops#3834).
 *
 * <p>A POST body rather than {@code ?search=}: the text is usually a colleague's name or email, and
 * a query string would copy it into every access log on the way. {@code limit} {@code null} ⇒ 10.
 */
public record AssigneeCandidateSearchRequest(
        @NotBlank @Size(min = 2, max = 64) String query,
        @Min(1) @Max(20) Integer limit) {
}
