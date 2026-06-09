package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Manual operator seed of a rollout failed-device queue item (Faz 22.5 #527
 * slice-1b write path, on top of #528's read foundation). The operator supplies
 * the routing keys, the class, the manual confidence and the per-class redacted
 * evidence object; the server derives everything else (state=new, retry=0,
 * max_retries + owner_role from class policy, classifier_version=manual:v1,
 * timestamps, actor_subject_hash) — the client cannot set state, retries or
 * actor identity. {@code evidence} is validated against the per-class allowlist
 * (Codex 019eaaf0) before persistence; an unknown field / non-redacted free
 * value / raw secret-PII marker is a 400.
 */
public record CreateRolloutFailureRequest(
        @NotBlank @Size(max = 128) String rolloutId,
        @NotBlank @Size(max = 128) String waveId,
        @NotNull UUID deviceId,
        @NotNull RolloutFailureClass failureClass,
        // contract wire form: "high" | "medium" | "low" (mapped via fromWire in
        // the service so the API accepts the schema value, not the enum NAME).
        @NotBlank String classificationConfidence,
        @NotNull JsonNode evidence) {
}
