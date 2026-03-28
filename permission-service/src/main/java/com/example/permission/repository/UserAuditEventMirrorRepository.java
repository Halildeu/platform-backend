package com.example.permission.repository;

import com.example.permission.model.UserAuditEventMirror;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuditEventMirrorRepository extends JpaRepository<UserAuditEventMirror, Long> {
}
