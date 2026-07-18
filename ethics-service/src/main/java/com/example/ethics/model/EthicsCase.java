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

    protected EthicsCase() {}
    public EthicsCase(UUID id, UUID orgId, Instant now) {
        this.id=id; this.orgId=orgId; this.productId="etik-speak"; this.status="NEW";
        this.createdAt=now; this.updatedAt=now;
    }
    public UUID getId(){return id;} public UUID getOrgId(){return orgId;}
    public String getStatus(){return status;} public String getAssignedTo(){return assignedTo;}
    public long getVersion(){return version;} public Instant getCreatedAt(){return createdAt;}
    public Instant getUpdatedAt(){return updatedAt;}
    public void setStatus(String status){this.status=status; this.updatedAt=Instant.now();}
    public void setAssignedTo(String assignedTo){this.assignedTo=assignedTo; this.updatedAt=Instant.now();}
}
