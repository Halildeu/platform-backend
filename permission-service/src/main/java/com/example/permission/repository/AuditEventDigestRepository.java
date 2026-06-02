package com.example.permission.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR-D2.5a (Codex 019e8708 AGREE option A constrained): Native PG queries for
 * weekly audit digest aggregation.
 *
 * <p>Uses ISO week boundaries (Monday 00:00 UTC start). All queries push
 * filtering to the database — NO client-side aggregation over paged events
 * (Codex No Fake Work guard).
 *
 * <p>Four query orchestration:
 * <ol>
 *   <li>weekTotals: per-week total + distinct user count</li>
 *   <li>actionBreakdown: per-week action histogram</li>
 *   <li>serviceBreakdown: per-week service histogram</li>
 *   <li>topUsers: per-week top-K by event count (ROW_NUMBER tie-break)</li>
 * </ol>
 */
@Repository
public class AuditEventDigestRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Per-week total + distinct user counts.
     *
     * @return ordered by week_start ASC; each row: [weekStart, isoYear, isoWeek, totalCount, distinctUserCount]
     */
    public List<Object[]> findWeeklyTotals(Instant dateFrom,
                                           Instant dateTo,
                                           String action,
                                           String service,
                                           String level,
                                           String userEmail,
                                           String search) {
        String sql = "SELECT " +
                "  DATE_TRUNC('week', occurred_at AT TIME ZONE 'UTC') AS week_start, " +
                "  EXTRACT(ISOYEAR FROM occurred_at AT TIME ZONE 'UTC')::int AS iso_year, " +
                "  EXTRACT(WEEK FROM occurred_at AT TIME ZONE 'UTC')::int AS iso_week, " +
                "  COUNT(*) AS total_count, " +
                "  COUNT(DISTINCT performed_by) AS distinct_user_count " +
                "FROM permission_audit_events " +
                "WHERE occurred_at >= :dateFrom AND occurred_at < :dateTo " +
                buildFilterClauses(action, service, level, userEmail, search) +
                "GROUP BY week_start, iso_year, iso_week " +
                "ORDER BY week_start ASC";

        Query query = bindCommonFilters(entityManager.createNativeQuery(sql),
                dateFrom, dateTo, action, service, level, userEmail, search);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /**
     * Per-week action histogram (action -> count).
     *
     * @return ordered by week_start ASC, then action ASC; each row: [weekStart, action, count]
     */
    public List<Object[]> findActionBreakdown(Instant dateFrom,
                                              Instant dateTo,
                                              String action,
                                              String service,
                                              String level,
                                              String userEmail,
                                              String search) {
        String sql = "SELECT " +
                "  DATE_TRUNC('week', occurred_at AT TIME ZONE 'UTC') AS week_start, " +
                "  COALESCE(action, '<null>') AS action_key, " +
                "  COUNT(*) AS action_count " +
                "FROM permission_audit_events " +
                "WHERE occurred_at >= :dateFrom AND occurred_at < :dateTo " +
                buildFilterClauses(action, service, level, userEmail, search) +
                "GROUP BY week_start, action_key " +
                "ORDER BY week_start ASC, action_key ASC";

        Query query = bindCommonFilters(entityManager.createNativeQuery(sql),
                dateFrom, dateTo, action, service, level, userEmail, search);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /**
     * Per-week service histogram (service -> count).
     */
    public List<Object[]> findServiceBreakdown(Instant dateFrom,
                                               Instant dateTo,
                                               String action,
                                               String service,
                                               String level,
                                               String userEmail,
                                               String search) {
        String sql = "SELECT " +
                "  DATE_TRUNC('week', occurred_at AT TIME ZONE 'UTC') AS week_start, " +
                "  COALESCE(service, '<null>') AS service_key, " +
                "  COUNT(*) AS service_count " +
                "FROM permission_audit_events " +
                "WHERE occurred_at >= :dateFrom AND occurred_at < :dateTo " +
                buildFilterClauses(action, service, level, userEmail, search) +
                "GROUP BY week_start, service_key " +
                "ORDER BY week_start ASC, service_key ASC";

        Query query = bindCommonFilters(entityManager.createNativeQuery(sql),
                dateFrom, dateTo, action, service, level, userEmail, search);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /**
     * Per-week top-K users by event count.
     *
     * <p>Deterministic tie-break: count DESC, performed_by ASC. Users with
     * null {@code performed_by} (system/anonymous events) excluded from top-K
     * leaderboard.
     *
     * @return ordered by week_start ASC, then rank ASC; each row: [weekStart, performedBy (Long), userEmail (String), eventCount]
     */
    public List<Object[]> findTopUsersPerWeek(Instant dateFrom,
                                              Instant dateTo,
                                              String action,
                                              String service,
                                              String level,
                                              String userEmail,
                                              String search,
                                              int topK) {
        String sql = "SELECT week_start, performed_by, user_email, event_count " +
                "FROM ( " +
                "  SELECT " +
                "    DATE_TRUNC('week', occurred_at AT TIME ZONE 'UTC') AS week_start, " +
                "    performed_by, " +
                "    MAX(user_email) AS user_email, " +
                "    COUNT(*) AS event_count, " +
                "    ROW_NUMBER() OVER ( " +
                "      PARTITION BY DATE_TRUNC('week', occurred_at AT TIME ZONE 'UTC') " +
                "      ORDER BY COUNT(*) DESC, performed_by ASC " +
                "    ) AS rn " +
                "  FROM permission_audit_events " +
                "  WHERE occurred_at >= :dateFrom AND occurred_at < :dateTo " +
                "    AND performed_by IS NOT NULL " +
                buildFilterClauses(action, service, level, userEmail, search) +
                "  GROUP BY week_start, performed_by " +
                ") ranked " +
                "WHERE rn <= :topK " +
                "ORDER BY week_start ASC, rn ASC";

        Query query = bindCommonFilters(entityManager.createNativeQuery(sql),
                dateFrom, dateTo, action, service, level, userEmail, search);
        query.setParameter("topK", topK);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    // ──────────────────────────────────────────────────────────────────────

    private static String buildFilterClauses(String action,
                                             String service,
                                             String level,
                                             String userEmail,
                                             String search) {
        StringBuilder sb = new StringBuilder();
        if (action != null && !action.isBlank()) {
            sb.append("AND action = :action ");
        }
        if (service != null && !service.isBlank()) {
            sb.append("AND service = :service ");
        }
        if (level != null && !level.isBlank()) {
            sb.append("AND level = :level ");
        }
        if (userEmail != null && !userEmail.isBlank()) {
            sb.append("AND user_email = :userEmail ");
        }
        if (search != null && !search.isBlank()) {
            // Loose search across user_email and details for parity with /api/audit/events
            sb.append("AND (user_email ILIKE :search OR details ILIKE :search) ");
        }
        return sb.toString();
    }

    private static Query bindCommonFilters(Query query,
                                           Instant dateFrom,
                                           Instant dateTo,
                                           String action,
                                           String service,
                                           String level,
                                           String userEmail,
                                           String search) {
        query.setParameter("dateFrom", Timestamp.from(dateFrom));
        query.setParameter("dateTo", Timestamp.from(dateTo));
        if (action != null && !action.isBlank()) {
            query.setParameter("action", action);
        }
        if (service != null && !service.isBlank()) {
            query.setParameter("service", service);
        }
        if (level != null && !level.isBlank()) {
            query.setParameter("level", level);
        }
        if (userEmail != null && !userEmail.isBlank()) {
            query.setParameter("userEmail", userEmail);
        }
        if (search != null && !search.isBlank()) {
            query.setParameter("search", "%" + search + "%");
        }
        return query;
    }

    /**
     * Convert a {@link Timestamp} (PG TIMESTAMP) to {@link Instant} treating
     * the value as UTC (no local timezone shift). The native query selects
     * {@code DATE_TRUNC('week', occurred_at AT TIME ZONE 'UTC')} which returns
     * a timestamp WITHOUT timezone marker on most JDBC drivers; we interpret
     * it as UTC explicitly to avoid host-timezone drift.
     */
    public static Instant timestampToInstantUtc(Timestamp ts) {
        if (ts == null) return null;
        // Treat the wall-clock value as UTC, NOT local.
        return OffsetDateTime.of(ts.toLocalDateTime(), ZoneOffset.UTC).toInstant();
    }

    /**
     * Build a fresh dense map preserving insertion order for stable JSON
     * serialization.
     */
    public static <K, V> Map<K, V> linkedMap() {
        return new LinkedHashMap<>();
    }

    /**
     * Helper: convert a raw breakdown row list into per-week dense maps.
     * Row shape: [weekStart (Timestamp), key (String), count (Number)].
     */
    public static Map<Instant, Map<String, Long>> bucketBreakdown(List<Object[]> rows) {
        Map<Instant, Map<String, Long>> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Instant weekStart = timestampToInstantUtc((Timestamp) r[0]);
            String key = (String) r[1];
            long count = ((Number) r[2]).longValue();
            out.computeIfAbsent(weekStart, k -> linkedMap()).put(key, count);
        }
        return out;
    }

    /**
     * Helper: convert top-users rows into per-week ordered lists.
     * Row shape: [weekStart (Timestamp), performedBy (Number), userEmail (String), eventCount (Number)].
     */
    public static Map<Instant, List<com.example.permission.dto.audit.TopUser>> bucketTopUsers(List<Object[]> rows) {
        Map<Instant, List<com.example.permission.dto.audit.TopUser>> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Instant weekStart = timestampToInstantUtc((Timestamp) r[0]);
            Long performedBy = r[1] == null ? null : ((Number) r[1]).longValue();
            String userEmail = (String) r[2];
            long count = ((Number) r[3]).longValue();
            out.computeIfAbsent(weekStart, k -> new ArrayList<>())
                    .add(new com.example.permission.dto.audit.TopUser(performedBy, userEmail, count));
        }
        return out;
    }
}
