package com.example.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Faz 35 ES-203/C — batch display-name lookup by Keycloak subject.
 *
 * <p>Subjects arrive in a POST body on purpose: a path or query parameter
 * would write the KC UUID into every access log between the caller and this
 * service. The batch is capped so the endpoint cannot be used to walk the
 * whole directory in one request.
 */
public record DisplayNameLookupRequest(
        @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 64) String> subjects) {
}
