package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ViewOnlyAuthorityHttpErrorMapperTest {
    private final ViewOnlyAuthorityHttpErrorMapper mapper = new ViewOnlyAuthorityHttpErrorMapper();

    @Test
    void storeFailureIsNeverReportedAsSigningFailure() {
        ViewOnlyAuthorityHttpErrorMapper.MappedError mapped = mapper.map(
                new ViewOnlyAuthorityException(
                        ViewOnlyAuthorityError.CHECKPOINT_STORE_UNAVAILABLE, "checkpoint store unavailable"));

        assertThat(mapped.httpStatus()).isEqualTo(503);
        assertThat(mapped.body().code()).isEqualTo("CHECKPOINT_STORE_UNAVAILABLE");
        assertThat(mapped.body().code()).isNotEqualTo("SIGNING_UNAVAILABLE");
        assertThat(mapped.body().retryable()).isTrue();
    }

    @Test
    void absentCheckpointIsNeverReportedAsSequenceConflict() {
        ViewOnlyAuthorityHttpErrorMapper.MappedError mapped = mapper.map(
                new ViewOnlyAuthorityException(
                        ViewOnlyAuthorityError.CHECKPOINT_NOT_FOUND, "checkpoint not found"));

        assertThat(mapped.httpStatus()).isEqualTo(404);
        assertThat(mapped.body().code()).isEqualTo("CHECKPOINT_NOT_FOUND");
        assertThat(mapped.body().code()).isNotEqualTo("CHECKPOINT_SEQUENCE_CONFLICT");
        assertThat(mapped.body().retryable()).isFalse();
    }

    @Test
    void unavailableServerOwnedAuthorityMaterialIsRetryableAndNeverAClientSchemaError() {
        ViewOnlyAuthorityHttpErrorMapper.MappedError mapped = mapper.map(
                new ViewOnlyAuthorityException(
                        ViewOnlyAuthorityError.AUTHORITY_MATERIAL_UNAVAILABLE,
                        "runtime trust root projection unavailable"));

        assertThat(mapped.httpStatus()).isEqualTo(503);
        assertThat(mapped.body().code()).isEqualTo("AUTHORITY_MATERIAL_UNAVAILABLE");
        assertThat(mapped.body().code()).isNotEqualTo("REQUEST_SCHEMA_INVALID");
        assertThat(mapped.body().retryable()).isTrue();
    }

    @Test
    void serializedErrorHasExactNonSecretShape() {
        ViewOnlyAuthorityErrorResponse body = mapper.map(
                new ViewOnlyAuthorityException(
                        ViewOnlyAuthorityError.SEQUENCE_CONFLICT, "sequence conflict"))
                .body();
        var json = new ObjectMapper().valueToTree(body);
        Set<String> fields = StreamSupport.stream(
                        ((Iterable<String>) json::fieldNames).spliterator(), false)
                .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
                "schemaVersion", "errorId", "code", "message", "retryable",
                "mutationCount", "credentialMaterialIncluded");
        assertThat(json.get("mutationCount").intValue()).isZero();
        assertThat(json.get("credentialMaterialIncluded").booleanValue()).isFalse();
    }

    @Test
    void invalidResponseCannotClaimMutationOrCredentialMaterial() {
        assertThatThrownBy(() -> new ViewOnlyAuthorityErrorResponse(
                "faz22.6.viewOnlyPreflightError.v1", java.util.UUID.randomUUID(),
                "CHECKPOINT_NOT_FOUND", "not found", false, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ViewOnlyAuthorityErrorResponse(
                "faz22.6.viewOnlyPreflightError.v1", java.util.UUID.randomUUID(),
                "CHECKPOINT_NOT_FOUND", "not found", false, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
