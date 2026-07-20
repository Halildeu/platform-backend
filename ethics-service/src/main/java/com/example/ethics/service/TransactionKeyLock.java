package com.example.ethics.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes one idempotency key for the full database transaction. */
@Component
public class TransactionKeyLock {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ConcurrentHashMap<Long, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public TransactionKeyLock(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public void lock(String namespaceAndKey) {
        long key = key64(namespaceAndKey);
        if (isPostgres()) {
            // PostgreSQL advisory transaction lock is connection-independent and released at commit/rollback.
            jdbc.execute((Connection connection) -> {
                try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                    statement.setLong(1, key);
                    statement.execute();
                    return null;
                }
            });
            return;
        }
        // H2-only test fallback. Keep the JVM lock until the Spring transaction actually completes.
        ReentrantLock lock = localLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                lock.unlock();
                if (!lock.hasQueuedThreads()) localLocks.remove(key, lock);
            }
        });
    }

    private boolean isPostgres() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception error) {
            throw new IllegalStateException("Idempotency lock database cannot be identified", error);
        }
    }

    private static long key64(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
