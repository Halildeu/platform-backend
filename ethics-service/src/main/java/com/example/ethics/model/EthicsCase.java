package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ethics_cases")
public class EthicsCase {
    @Id private UUID id;
    @Column(name="org_id", nullable=false) private UUID orgId;
    @Column(name="product_id", nullable=false, updatable=false) private String productId;
    @Column(nullable=false) private String status;
    @Column(name="assigned_to") private String assignedTo;
    @Version private long version;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @Column(name="acknowledged_at") private Instant acknowledgedAt;
    @Column private String outcome;
    @Column(name="closed_at") private Instant closedAt;

    protected EthicsCase() {}
    public EthicsCase(UUID id, UUID orgId, Instant now) {
        this.id=id; this.orgId=orgId; this.productId="etik-speak"; this.status=CaseLifecycle.NEW;
        this.createdAt=now; this.updatedAt=now;
    }
    public UUID getId(){return id;} public UUID getOrgId(){return orgId;}
    public String getStatus(){return status;} public String getAssignedTo(){return assignedTo;}
    public long getVersion(){return version;} public Instant getCreatedAt(){return createdAt;}
    public Instant getUpdatedAt(){return updatedAt;}
    public Instant getAcknowledgedAt(){return acknowledgedAt;}
    public String getOutcome(){return outcome;} public Instant getClosedAt(){return closedAt;}
    public void setAssignedTo(String assignedTo){this.assignedTo=assignedTo; this.updatedAt=Instant.now();}

    // `acknowledgedAt` has no setter on purpose. It is written by exactly one atomic
    // statement — EthicsCaseRepository#markAcknowledged — which stamps only while the
    // field is still null. Nothing here can move a deadline that has already started.

    /**
     * Moves the case and keeps the closure fields in step with it, so a case can never
     * be closed without a finding, nor carry a stale finding after being reopened. Whether
     * {@code status -> next} is legal at all is {@link CaseLifecycle}'s decision; this
     * applies the consequences of a move already found legal.
     */
    public void transitionTo(String next, String outcome, Instant when){
        this.status=next; this.updatedAt=when;
        if(CaseLifecycle.CLOSED.equals(next)){ this.outcome=outcome; this.closedAt=when; }
        else { this.outcome=null; this.closedAt=null; }
    }
}
