package com.serban.notify.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Notification template — versionable, immutable per version (ADR-0013 D46 #9).
 */
@Entity
@Table(name = "notification_template", schema = "notify",
       uniqueConstraints = @UniqueConstraint(name = "uq_template_version_locale",
                                              columnNames = {"template_id", "version", "locale"}))
public class NotificationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false, length = 128)
    private String templateId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 16)
    private String locale;

    @Column(columnDefinition = "text")
    private String subject;

    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    @Column(name = "external_allowed", nullable = false)
    private boolean externalAllowed = false;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public boolean isExternalAllowed() { return externalAllowed; }
    public void setExternalAllowed(boolean externalAllowed) { this.externalAllowed = externalAllowed; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotificationTemplate that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode();
    }
}
