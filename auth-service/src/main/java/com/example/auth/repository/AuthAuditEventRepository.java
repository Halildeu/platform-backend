package com.example.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.auth.model.AuthAuditEvent;

public interface AuthAuditEventRepository extends JpaRepository<AuthAuditEvent, Long> {
}
