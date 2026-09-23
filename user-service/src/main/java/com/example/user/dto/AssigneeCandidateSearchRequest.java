package com.example.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Faz 24 (gitops#3834) — people-picker search on behalf of a signed-in user.
 *
 * <p>The requester's Keycloak subject and the typed text arrive in a POST body on purpose: in a
 * path or query string they would be written into every access log between the caller and this
 * service. The text and the page are bounded so one request cannot walk the directory; the
 * minimum text length is checked after trimming, in the controller.
 *
 * @param requesterSubject Keycloak subject of the user the calling service acts for
 * @param query            text typed into the picker, matched against name and email
 * @param limit            page size; {@code null} ⇒ the default
 */
public record AssigneeCandidateSearchRequest(
        @NotBlank @Size(max = 64) String requesterSubject,
        @NotBlank @Size(max = 64) String query,
        @Min(1) @Max(20) Integer limit) {
}
