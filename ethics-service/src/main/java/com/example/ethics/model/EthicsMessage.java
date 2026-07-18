package com.example.ethics.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="ethics_messages", uniqueConstraints=@UniqueConstraint(name="uq_ethics_message_idempotency", columnNames={"case_id","author_type","idempotency_key"}))
public class EthicsMessage {
    @Id private UUID id;
    @Column(name="case_id", nullable=false) private UUID caseId;
    @Column(name="author_type", nullable=false) private String authorType;
    @Column(nullable=false) private String visibility;
    @Column(nullable=false, length=16000) private String body;
    @Column(name="idempotency_key", nullable=false, length=200) private String idempotencyKey;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected EthicsMessage() {}
    public EthicsMessage(UUID id, UUID caseId, String authorType, String visibility, String body, String idempotencyKey, Instant now){this.id=id;this.caseId=caseId;this.authorType=authorType;this.visibility=visibility;this.body=body;this.idempotencyKey=idempotencyKey;this.createdAt=now;}
    public UUID getId(){return id;} public UUID getCaseId(){return caseId;} public String getAuthorType(){return authorType;}
    public String getVisibility(){return visibility;} public String getBody(){return body;} public Instant getCreatedAt(){return createdAt;}
}
