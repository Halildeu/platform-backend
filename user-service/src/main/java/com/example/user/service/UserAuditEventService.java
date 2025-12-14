package com.example.user.service;

import com.example.user.model.UserAuditEvent;
import com.example.user.repository.UserAuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAuditEventService {
    private final UserAuditEventRepository repository;

    public UserAuditEventService(UserAuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserAuditEvent recordUpdateEvent(Long performedBy, Long targetUserId, String details) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType("USER_UPDATE");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails(details);
        return repository.save(event);
    }

    @Transactional
    public UserAuditEvent recordActivationEvent(Long performedBy, Long targetUserId, boolean active) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType(active ? "USER_ACTIVATE" : "USER_DEACTIVATE");
        event.setPerformedBy(performedBy);
        event.setTargetUserId(targetUserId);
        event.setDetails("User %s by %d".formatted(active ? "activated" : "deactivated", performedBy));
        return repository.save(event);
    }

    @Transactional
    public UserAuditEvent recordExportEvent(Long performedBy, String details, boolean success) {
        UserAuditEvent event = new UserAuditEvent();
        event.setEventType(success ? "USER_EXPORT" : "USER_EXPORT_FAILED");
        event.setPerformedBy(performedBy);
        event.setDetails(details);
        return repository.save(event);
    }
}
