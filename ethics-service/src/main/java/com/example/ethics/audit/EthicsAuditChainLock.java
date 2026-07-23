package com.example.ethics.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL transaction-scoped per-org lock. It serializes chain-tail read +
 * append across replicas without introducing a mutable chain-head table.
 */
@Component
public class EthicsAuditChainLock {
    private final JdbcTemplate jdbc;

    public EthicsAuditChainLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(UUID orgId) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "faz35-ethics-worm:" + orgId);
                statement.execute();
            }
            return null;
        });
    }
}
