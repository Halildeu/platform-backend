package com.example.ethics.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="reveal_requests")
public class RevealRequest {
    @Id private UUID id;
    @Column(name="case_id", nullable=false) private UUID caseId;
    @Column(name="requester_subject", nullable=false) private String requesterSubject;
    @Column(name="requester_name", nullable=false) private String requesterName;
    @Column(name="legal_basis", nullable=false) private String legalBasis;
    @Column(name="legal_authority", nullable=false) private String legalAuthority;
    @Column(name="reference_number", nullable=false) private String referenceNumber;
    @Column(name="justification", nullable=false) private String justification;
    @Column(nullable=false) private String status;
    @Column(name="requested_at", nullable=false) private Instant requestedAt;
    @Column(name="first_approver_subject") private String firstApproverSubject;
    @Column(name="first_approver_name") private String firstApproverName;
    @Column(name="first_approver_role") private String firstApproverRole;
    @Column(name="first_approved_at") private Instant firstApprovedAt;
    @Column(name="second_approver_subject") private String secondApproverSubject;
    @Column(name="second_approver_name") private String secondApproverName;
    @Column(name="second_approver_role") private String secondApproverRole;
    @Column(name="second_approved_at") private Instant secondApprovedAt;
    @Column(name="rejected_at") private Instant rejectedAt;
    @Column(name="rejected_by_subject") private String rejectedBySubject;
    @Column(name="rejection_reason") private String rejectionReason;
    @Column(name="executed_at") private Instant executedAt;
    @Column(name="executed_by_subject") private String executedBySubject;
    @Version private long version;

    protected RevealRequest() {}

    public RevealRequest(UUID id, UUID caseId, String requesterSubject, String requesterName,
            String legalBasis, String legalAuthority, String referenceNumber, String justification,
            Instant requestedAt) {
        this.id=id;this.caseId=caseId;this.requesterSubject=requesterSubject;
        this.requesterName=requesterName;this.legalBasis=legalBasis;this.legalAuthority=legalAuthority;
        this.referenceNumber=referenceNumber;this.justification=justification;
        this.status="PENDING";this.requestedAt=requestedAt;
    }

    public UUID getId(){return id;}
    public UUID getCaseId(){return caseId;}
    public String getRequesterSubject(){return requesterSubject;}
    public String getRequesterName(){return requesterName;}
    public String getLegalBasis(){return legalBasis;}
    public String getLegalAuthority(){return legalAuthority;}
    public String getReferenceNumber(){return referenceNumber;}
    public String getJustification(){return justification;}
    public String getStatus(){return status;}
    public Instant getRequestedAt(){return requestedAt;}
    public String getFirstApproverSubject(){return firstApproverSubject;}
    public String getFirstApproverName(){return firstApproverName;}
    public String getFirstApproverRole(){return firstApproverRole;}
    public Instant getFirstApprovedAt(){return firstApprovedAt;}
    public String getSecondApproverSubject(){return secondApproverSubject;}
    public String getSecondApproverName(){return secondApproverName;}
    public Instant getSecondApprovedAt(){return secondApprovedAt;}
    public String getRejectedBySubject(){return rejectedBySubject;}
    public String getRejectionReason(){return rejectionReason;}
    public Instant getRejectedAt(){return rejectedAt;}
    public Instant getExecutedAt(){return executedAt;}
    public String getExecutedBySubject(){return executedBySubject;}
    public long getVersion(){return version;}

    public void recordFirstApproval(String subject, String name, String role, Instant now) {
        this.firstApproverSubject=subject;this.firstApproverName=name;this.firstApproverRole=role;
        this.firstApprovedAt=now;this.status="ONE_APPROVED";
    }
    public void recordSecondApproval(String subject, String name, String role, Instant now) {
        this.secondApproverSubject=subject;this.secondApproverName=name;this.secondApproverRole=role;
        this.secondApprovedAt=now;this.status="READY";
    }
    public void reject(String subject, String reason, Instant now) {
        this.rejectedBySubject=subject;this.rejectionReason=reason;this.rejectedAt=now;this.status="REJECTED";
    }
    public void markExecuted(String subject, Instant now) {
        this.executedBySubject=subject;this.executedAt=now;this.status="EXECUTED";
    }
}
