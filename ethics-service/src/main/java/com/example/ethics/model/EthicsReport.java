package com.example.ethics.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="ethics_reports")
public class EthicsReport {
    @Id private UUID id;
    @Column(name="case_id", nullable=false, unique=true) private UUID caseId;
    @Column(nullable=false) private String mode;
    @Column(nullable=false) private String category;
    @Column(nullable=false, length=240) private String subject;
    @Column(nullable=false, length=16000) private String narrative;
    @Column(nullable=false, length=12) private String locale;
    @Column(name="notice_version", nullable=false, length=80) private String noticeVersion;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected EthicsReport() {}
    public EthicsReport(UUID id, UUID caseId, String mode, String category, String subject, String narrative, String locale, String noticeVersion, Instant now){
        this.id=id;this.caseId=caseId;this.mode=mode;this.category=category;this.subject=subject;this.narrative=narrative;this.locale=locale;this.noticeVersion=noticeVersion;this.createdAt=now;
    }
    public UUID getCaseId(){return caseId;} public String getMode(){return mode;} public String getCategory(){return category;}
    public String getSubject(){return subject;} public String getNarrative(){return narrative;} public Instant getCreatedAt(){return createdAt;}
    public String getLocale(){return locale;} public String getNoticeVersion(){return noticeVersion;}
}
