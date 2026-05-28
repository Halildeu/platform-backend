package com.example.endpointadmin.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * Wave-12 PR-5 — append-only history entry for a policy-change approval.
 * The {@code kind} property discriminates between the five concrete
 * shapes; mirrors the platform-web {@code DecisionRecord} union.
 *
 * <p>Serialised over the wire with {@code kind} as a lowercase
 * snake_case discriminator (e.g. {@code "kind": "request_changes"}) so
 * the platform-web contract round-trips verbatim.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DecisionRecordDto.Approve.class, name = "approve"),
        @JsonSubTypes.Type(value = DecisionRecordDto.Reject.class, name = "reject"),
        @JsonSubTypes.Type(value = DecisionRecordDto.RequestChanges.class,
                name = "request_changes"),
        @JsonSubTypes.Type(value = DecisionRecordDto.Delegate.class, name = "delegate"),
        @JsonSubTypes.Type(value = DecisionRecordDto.Attest.class, name = "attest")
})
public sealed interface DecisionRecordDto {

    ApprovalActorDto actor();

    Instant at();

    record Approve(ApprovalActorDto actor, Instant at, String reason)
            implements DecisionRecordDto {
    }

    record Reject(ApprovalActorDto actor, Instant at, String reason)
            implements DecisionRecordDto {
    }

    record RequestChanges(ApprovalActorDto actor, Instant at, String reason)
            implements DecisionRecordDto {
    }

    record Delegate(ApprovalActorDto actor, ApprovalActorDto delegateTo,
                    Instant at, String reason) implements DecisionRecordDto {
    }

    record Attest(ApprovalActorDto actor, Instant at, String statement,
                  Instant acceptedAt) implements DecisionRecordDto {
    }
}
