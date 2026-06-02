package com.example.endpointadmin.grid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the endpoint-device grid's queryable surface
 * (board #1154 PR-2). Every column the {@code /query} (SSRM) and
 * {@code /export} endpoints can select, filter, sort or export is declared
 * here exactly once.
 *
 * <p><strong>Security spine.</strong> The {@code colId} a client sends is
 * NEVER interpolated into SQL — it is only ever used as a lookup key into
 * this registry. The mapped {@link GridColumn#sqlExpr()} is a trusted,
 * compile-time-constant SQL expression (e.g. {@code d.hostname}); filter
 * values are bound as named parameters. A {@code colId} that is not in the
 * registry is rejected fail-closed (the builder raises
 * {@code INVALID_GRID_FILTER} / {@code INVALID_GRID_SORT}).
 *
 * <p>The {@code sqlExpr} table aliases are fixed by
 * {@code DeviceGridQueryBuilder}: {@code d} = endpoint_devices,
 * {@code h} = latest device-health snapshot (LATERAL), {@code o} = latest
 * outdated-software snapshot (LATERAL), {@code pe} = latest compliance
 * evaluation (LATERAL, WEB-015 v2-a / schema v3), {@code ac} = latest
 * app-control snapshot (LATERAL, WEB-015 v2-a / schema v3).
 *
 * <p>{@code header} is the Turkish display label used by the {@code /export}
 * path (PR-2b) — it is resolved SERVER-side from this registry, never taken
 * from the client request.
 */
public final class DeviceGridColumns {

    private DeviceGridColumns() {}

    /**
     * Device-grid query/export schema version. Bumped from 2 to 3 with
     * WEB-015 v2-a (Codex 019e8785 iter-2): adds BE-025 prohibited-
     * software + AG-041 app-control LATERAL summary columns.
     */
    public static final int SCHEMA_VERSION = 3;

    /** Cached SHA-256 over the canonical, comma-joined column id list. */
    private static volatile String CACHED_COLUMN_IDS_HASH;

    /** Filterability class — pins which AG Grid filter family a column accepts. */
    public enum ColumnType {
        /** varchar columns — AG Grid {@code text} filter family. */
        TEXT,
        /** string-enum columns (os_type, status) — AG Grid {@code set} filter (IN). */
        ENUM,
        /** integer columns — AG Grid {@code number} filter family. */
        NUMBER,
        /** boolean columns — AG Grid {@code set} filter over {true,false}. */
        BOOLEAN,
        /** timestamptz columns — AG Grid {@code date} filter (ISO instants). */
        TIMESTAMP
    }

    /**
     * One grid column.
     *
     * @param colId          the stable client-facing column id (also the SQL
     *                       SELECT alias and the JSON row key)
     * @param sqlExpr        trusted constant SQL expression for SELECT + sort
     * @param filterExpr     trusted constant SQL expression for the WHERE
     *                       clause — usually identical to {@code sqlExpr}, but
     *                       a non-text column addressed by a text filter must
     *                       cast (e.g. {@code device_id}'s UUID needs
     *                       {@code d.id::text} so {@code lower(...)} is valid
     *                       and a client filter can never 500)
     * @param type           filterability class
     * @param quickFilterable whether the global quick-filter scans this column
     * @param header         Turkish export header (server-resolved)
     */
    public record GridColumn(String colId, String sqlExpr, String filterExpr, ColumnType type,
                             boolean quickFilterable, String header) {
        /** Convenience: filterExpr defaults to sqlExpr (the common case). */
        public GridColumn(String colId, String sqlExpr, ColumnType type,
                          boolean quickFilterable, String header) {
            this(colId, sqlExpr, sqlExpr, type, quickFilterable, header);
        }
    }

    // Order here is the canonical export column order (raw export).
    private static final List<GridColumn> COLUMNS = List.of(
            // device_id is a UUID column: SELECT/sort use d.id, but a text
            // filter must cast (d.id::text) so lower(...) is valid — an
            // allowlisted column must never produce invalid SQL.
            new GridColumn("device_id", "d.id", "d.id::text", ColumnType.TEXT, false, "Cihaz ID"),
            new GridColumn("hostname", "d.hostname", ColumnType.TEXT, true, "Bilgisayar Adı"),
            new GridColumn("display_name", "d.display_name", ColumnType.TEXT, true, "Görünen Ad"),
            new GridColumn("os_type", "d.os_type", ColumnType.ENUM, false, "İşletim Sistemi"),
            new GridColumn("os_version", "d.os_version", ColumnType.TEXT, true, "OS Sürümü"),
            new GridColumn("agent_version", "d.agent_version", ColumnType.TEXT, true, "Ajan Sürümü"),
            new GridColumn("domain_name", "d.domain_name", ColumnType.TEXT, true, "Etki Alanı"),
            new GridColumn("status", "d.status", ColumnType.ENUM, false, "Durum"),
            new GridColumn("last_seen_at", "d.last_seen_at", ColumnType.TIMESTAMP, false, "Son Görülme"),
            // device-health (AG-033) latest-per-device summary
            new GridColumn("health_supported", "h.supported", ColumnType.BOOLEAN, false, "Sağlık Destekli"),
            new GridColumn("health_probe_complete", "h.probe_complete", ColumnType.BOOLEAN, false, "Sağlık Prob Tam"),
            new GridColumn("health_any_low_disk", "h.any_low_disk", ColumnType.BOOLEAN, false, "Düşük Disk"),
            new GridColumn("health_memory_used_percent", "h.memory_used_percent", ColumnType.NUMBER, false, "Bellek %"),
            new GridColumn("health_memory_high_pressure", "h.memory_high_pressure", ColumnType.BOOLEAN, false, "Bellek Baskısı"),
            new GridColumn("health_uptime_days", "h.uptime_days", ColumnType.NUMBER, false, "Çalışma (gün)"),
            new GridColumn("health_long_uptime_warning", "h.long_uptime_warning", ColumnType.BOOLEAN, false, "Uzun Çalışma Uyarısı"),
            new GridColumn("health_collected_at", "h.collected_at", ColumnType.TIMESTAMP, false, "Sağlık Ölçüm Zamanı"),
            // outdated-software (AG-036) latest-per-device summary
            new GridColumn("outdated_supported", "o.supported", ColumnType.BOOLEAN, false, "Güncellik Destekli"),
            new GridColumn("outdated_probe_complete", "o.probe_complete", ColumnType.BOOLEAN, false, "Güncellik Prob Tam"),
            new GridColumn("outdated_upgrade_count", "o.upgrade_count", ColumnType.NUMBER, false, "Güncellenebilir Yazılım"),
            new GridColumn("outdated_upgrade_truncated", "o.upgrade_truncated", ColumnType.BOOLEAN, false, "Güncellik Liste Kırpıldı"),
            new GridColumn("outdated_collected_at", "o.collected_at", ColumnType.TIMESTAMP, false, "Güncellik Ölçüm Zamanı"),
            // WEB-015 v2-a (schema v3) BE-025 prohibited-software latest-evaluation
            // summary. pe.id IS NULL => status NO_EVALUATION + decision/count NULL;
            // otherwise OK + persisted decision + JSONB-safe array length. Codex
            // 019e8785 iter-2: read from EndpointComplianceEvaluation, NOT
            // recompute live; mirrors BE-025 per-device GET semantics.
            new GridColumn("prohibited_status",
                    "CASE WHEN pe.id IS NULL THEN 'NO_EVALUATION' ELSE 'OK' END",
                    ColumnType.ENUM, false, "Yasaklı Yazılım Durumu"),
            new GridColumn("prohibited_decision", "pe.decision",
                    ColumnType.ENUM, false, "Uygunluk Kararı"),
            new GridColumn("prohibited_findings_count",
                    "CASE"
                            + " WHEN pe.id IS NULL THEN NULL"
                            + " WHEN jsonb_typeof(pe.evidence #> '{matchedItems,prohibitedInstalled}') = 'array'"
                            + "   THEN jsonb_array_length(pe.evidence #> '{matchedItems,prohibitedInstalled}')"
                            + " ELSE 0"
                            + " END",
                    ColumnType.NUMBER, false, "Yasaklı Yazılım Bulgu Sayısı"),
            // WEB-015 v2-a (schema v3) AG-041 app-control latest-snapshot summary.
            // ac.id IS NULL => both columns NULL (NOT synthesized to UNKNOWN per
            // Codex 019e8785 iter-2 absorb); persisted UNKNOWN remains a valid
            // operator signal distinct from no-snapshot.
            new GridColumn("app_control_wdac_mode", "ac.wdac_mode",
                    ColumnType.ENUM, false, "WDAC Modu"),
            new GridColumn("app_control_app_id_svc_state", "ac.app_locker_app_id_svc_state",
                    ColumnType.ENUM, false, "AppIDSvc Durumu")
    );

    private static final Map<String, GridColumn> BY_ID;
    static {
        Map<String, GridColumn> m = new LinkedHashMap<>();
        for (GridColumn c : COLUMNS) {
            m.put(c.colId(), c);
        }
        BY_ID = Map.copyOf(m);
    }

    /** All columns in canonical (raw-export) order. */
    public static List<GridColumn> all() {
        return COLUMNS;
    }

    /** All column ids in canonical order. */
    public static List<String> allColumnIds() {
        return COLUMNS.stream().map(GridColumn::colId).toList();
    }

    /** Lookup by client {@code colId}; {@code null} if not in the allowlist. */
    public static GridColumn byId(String colId) {
        return colId == null ? null : BY_ID.get(colId);
    }

    /** Whether {@code colId} is a known, allowlisted column. */
    public static boolean isKnown(String colId) {
        return colId != null && BY_ID.containsKey(colId);
    }

    /**
     * Deterministic SHA-256 over the canonical, comma-joined column id
     * list. Codex 019e8785 iter-2: audit metadata pins both
     * {@link #SCHEMA_VERSION} and this hash so a stale-or-tampered
     * registry on a different pod can be detected from the audit row
     * alone. Computed lazily and cached (registry is immutable).
     */
    public static String columnIdsHash() {
        String cached = CACHED_COLUMN_IDS_HASH;
        if (cached != null) return cached;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(
                    String.join(",", allColumnIds()).getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            CACHED_COLUMN_IDS_HASH = hex;
            return hex;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }
}
