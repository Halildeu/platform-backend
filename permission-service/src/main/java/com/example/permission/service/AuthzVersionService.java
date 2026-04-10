package com.example.permission.service;

import com.example.permission.model.AuthzSyncVersion;
import com.example.permission.repository.AuthzSyncVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthzVersionService {

    private static final Logger log = LoggerFactory.getLogger(AuthzVersionService.class);
    private static final int VERSION_ROW_ID = 1;

    private final AuthzSyncVersionRepository repository;

    public AuthzVersionService(AuthzSyncVersionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public long getCurrentVersion() {
        return repository.findById(VERSION_ROW_ID)
                .map(AuthzSyncVersion::getVersion)
                .orElse(0L);
    }

    @Transactional
    public long incrementVersion() {
        int updated = repository.incrementVersion();
        if (updated == 0) {
            log.warn("authz_sync_version row missing; seeding with version=2");
            AuthzSyncVersion seed = new AuthzSyncVersion();
            seed.setVersion(2L);
            repository.save(seed);
            return 2L;
        }
        long newVersion = getCurrentVersion();
        log.debug("authz_version incremented to {}", newVersion);
        return newVersion;
    }
}
