package com.example.permission.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "authz_sync_version")
public class AuthzSyncVersion {

    @Id
    @Column(name = "id", nullable = false)
    private Integer id = 1;

    @Column(name = "version", nullable = false)
    private Long version = 1L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AuthzSyncVersion() {}

    public Integer getId() { return id; }
    public Long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setVersion(Long version) {
        this.version = version;
        this.updatedAt = Instant.now();
    }
}
