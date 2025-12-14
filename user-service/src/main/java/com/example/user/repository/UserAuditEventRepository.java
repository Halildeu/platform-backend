package com.example.user.repository;

import com.example.user.model.UserAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuditEventRepository extends JpaRepository<UserAuditEvent, Long> {
}

