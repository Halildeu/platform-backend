package com.example.schema.catalog;

import com.example.schema.model.ChangeDataInfo;
import com.example.schema.model.CheckConstraintInfo;
import com.example.schema.model.ColumnInfo;
import com.example.schema.model.DatabaseOptionsInfo;
import com.example.schema.model.DefaultConstraintInfo;
import com.example.schema.model.ForeignKeyInfo;
import com.example.schema.model.IndexInfo;
import com.example.schema.model.ObjectInfo;
import com.example.schema.model.StorageInfo;
import com.example.schema.model.TableInfo;
import com.example.schema.model.UniqueConstraintInfo;
import com.example.schema.model.UniqueConstraintType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads an Oracle data dictionary through the {@code ALL_*} views.
 *
 * <p>Written against the live IFS ERP instance (Oracle 19c, owner {@code IFSAPP}),
 * whose shape drove three decisions that are not obvious from the MSSQL reader:
 *
 * <ol>
 *   <li><strong>Views are the object set, not tables.</strong> The measured
 *       instance exposes 10,885 views, 6,304 packages and exactly one table —
 *       {@code TOAD_PLAN_TABLE}, a tool artefact. IFS publishes business data
 *       through its {@code *_QRY} view layer and the base tables are not visible
 *       to a reporting account, so a reader built on {@code ALL_TABLES} alone
 *       would show a single piece of debris. Both object classes are therefore
 *       unioned, and {@code ALL_TAB_COLUMNS} covers columns for both.</li>
 *   <li><strong>View source must come from the {@code LONG} column.</strong>
 *       {@code ALL_VIEWS} offers {@code TEXT} ({@code LONG}) and {@code TEXT_VC}
 *       (capped at 4000 chars). 813 of the measured views exceed that cap and the
 *       largest is 76,228 characters, so {@code TEXT_VC} would silently hand the
 *       relationship parser a truncated view. {@code TEXT} is selected as the sole
 *       projected column because a JDBC {@code LONG} must be read before any other
 *       column of the same row.</li>
 *   <li><strong>There is no declared relationship metadata to lean on.</strong>
 *       The measured owner carries 10,800 {@code O} (read-only view) constraints
 *       and zero primary, foreign or unique keys, so the graph can only come from
 *       parsing view SQL. The queries below are still written generically — a
 *       different Oracle instance may well declare keys — but callers must treat
 *       an empty foreign-key list here as "this schema declares none", not as a
 *       failure.</li>
 * </ol>
 *
 * <p>Surfaces with no faithful Oracle equivalent return empty rather than a
 * guess, and say so at the call site. Only {@link #extractTables} is fatal; the
 * snapshot builder degrades on the rest.
 */
public class OracleCatalogReader implements CatalogReader {

    private static final Logger log = LoggerFactory.getLogger(OracleCatalogReader.class);

    private final String sourceId;
    private final NamedParameterJdbcTemplate jdbc;
    private final String defaultSchema;

    public OracleCatalogReader(String sourceId, NamedParameterJdbcTemplate jdbc, String defaultSchema) {
        this.sourceId = sourceId;
        this.jdbc = jdbc;
        this.defaultSchema = defaultSchema;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String engine() {
        return "oracle";
    }

    @Override
    public String defaultSchema() {
        return defaultSchema;
    }

    private String target(String schema) {
        // Oracle folds unquoted identifiers to upper case; the dictionary stores
        // them that way, so a lower-case owner from the caller finds nothing.
        String value = (schema == null || schema.isBlank()) ? defaultSchema : schema;
        return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static Integer integerOrNull(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    // ---------------------------------------------------------------- tables

    @Override
    public Map<String, TableInfo> extractTables(String schema) {
        String owner = target(schema);
        log.info("[{}] Oracle: extracting tables and views for owner '{}'", sourceId, owner);

        // ALL_TAB_COLUMNS covers tables and views alike. The join to ALL_OBJECTS
        // keeps the result to the two object classes we present and lets the
        // caller see which is which; the PK subquery is a left join because the
        // measured instance declares none.
        String sql = """
            SELECT c.TABLE_NAME    AS object_name,
                   o.OBJECT_TYPE   AS object_type,
                   c.COLUMN_NAME   AS column_name,
                   c.DATA_TYPE     AS data_type,
                   c.DATA_LENGTH   AS data_length,
                   c.DATA_PRECISION AS data_precision,
                   c.DATA_SCALE    AS data_scale,
                   c.NULLABLE      AS nullable,
                   c.COLUMN_ID     AS ordinal,
                   CASE WHEN pk.COLUMN_NAME IS NULL THEN 0 ELSE 1 END AS is_pk,
                   cm.COMMENTS     AS col_comment
              FROM ALL_TAB_COLUMNS c
              JOIN ALL_OBJECTS o
                ON o.OWNER = c.OWNER
               AND o.OBJECT_NAME = c.TABLE_NAME
               AND o.OBJECT_TYPE IN ('TABLE', 'VIEW')
              LEFT JOIN ALL_COL_COMMENTS cm
                ON cm.OWNER = c.OWNER
               AND cm.TABLE_NAME = c.TABLE_NAME
               AND cm.COLUMN_NAME = c.COLUMN_NAME
              LEFT JOIN (
                   SELECT cc.OWNER, cc.TABLE_NAME, cc.COLUMN_NAME
                     FROM ALL_CONSTRAINTS ct
                     JOIN ALL_CONS_COLUMNS cc
                       ON cc.OWNER = ct.OWNER
                      AND cc.CONSTRAINT_NAME = ct.CONSTRAINT_NAME
                    WHERE ct.CONSTRAINT_TYPE = 'P'
                      AND ct.OWNER = :owner
              ) pk
                ON pk.OWNER = c.OWNER
               AND pk.TABLE_NAME = c.TABLE_NAME
               AND pk.COLUMN_NAME = c.COLUMN_NAME
             WHERE c.OWNER = :owner
             ORDER BY c.TABLE_NAME, c.COLUMN_ID
            """;

        Map<String, List<ColumnInfo>> columnsByObject = new LinkedHashMap<>();
        jdbc.query(sql, Map.of("owner", owner), rs -> {
            String objectName = rs.getString("object_name");
            // Oracle JDBC surfaces every NUMBER column as BigDecimal, never as
            // Integer, so a direct (Integer) cast is a ClassCastException on the
            // very first row. Measured live: the dictionary query ran for 21s
            // (the ORDER BY sorts 205,874 rows before the first one arrives) and
            // then the snapshot collapsed on that cast.
            Integer precision = integerOrNull(rs.getObject("data_precision"));
            Integer scale = integerOrNull(rs.getObject("data_scale"));
            // gitops#3631: IFS keeps the column's own metadata in the dictionary comment.
            // The measured instance declares no PK constraints on its views (they live on
            // the _TAB base tables this account cannot see), so the key flag in FLAGS= is
            // the only primary-key signal available — it is OR-ed with the constraint, never
            // instead of it. PROMPT= becomes the label; the raw comment travels as well.
            IfsColumnComment comment = IfsColumnComment.parse(rs.getString("col_comment"));
            columnsByObject.computeIfAbsent(objectName, k -> new ArrayList<>())
                .add(new ColumnInfo(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    rs.getInt("data_length"),
                    precision,
                    scale,
                    null,                       // collation — per-column collation is not read here
                    "Y".equals(rs.getString("nullable")),
                    false,                      // identity — Oracle identity lives in ALL_TAB_IDENTITY_COLS
                    null,
                    null,
                    rs.getInt("is_pk") == 1 || comment.keyColumn(),
                    null,                       // default expression — extractDefaultConstraints
                    null,                       // computed expression — virtual columns not read here
                    false,
                    false,                      // sparse — no Oracle equivalent
                    rs.getInt("ordinal"),
                    comment.raw(),
                    comment.label()
                ));
        });

        // Object comments in one pass (ALL_TAB_COMMENTS covers views too); IFS fills
        // most of them with the logical unit's description.
        Map<String, String> objectComments = new LinkedHashMap<>();
        jdbc.query("""
            SELECT TABLE_NAME, COMMENTS
              FROM ALL_TAB_COMMENTS
             WHERE OWNER = :owner
               AND COMMENTS IS NOT NULL
            """, Map.of("owner", owner), rs -> {
                objectComments.put(rs.getString("TABLE_NAME"), rs.getString("COMMENTS"));
            });

        Map<String, TableInfo> result = new LinkedHashMap<>();
        columnsByObject.forEach((name, cols) -> result.put(name,
            new TableInfo(name, owner, cols, null, cols.size(), objectComments.get(name))));

        log.info("[{}] Oracle: {} objects, {} columns for owner '{}'", sourceId, result.size(),
            result.values().stream().mapToInt(t -> t.columns().size()).sum(), owner);
        return result;
    }

    @Override
    public Set<String> getTableNames(String schema) {
        String owner = target(schema);
        return new LinkedHashSet<>(jdbc.queryForList("""
            SELECT OBJECT_NAME
              FROM ALL_OBJECTS
             WHERE OWNER = :owner
               AND OBJECT_TYPE IN ('TABLE', 'VIEW')
             ORDER BY OBJECT_NAME
            """, Map.of("owner", owner), String.class));
    }

    /**
     * Owners that are Oracle's own, on an instance too old for {@code ALL_USERS.ORACLE_MAINTAINED}
     * (12c+). The dictionary flag is preferred; this list is the fallback and is also applied
     * on top of it, because SYS and SYSTEM are never a schema a reporting user means to browse.
     */
    static final Set<String> ORACLE_OWNED_SCHEMAS = Set.of(
        "SYS", "SYSTEM", "MDSYS", "CTXSYS", "WMSYS", "XDB", "OLAPSYS", "LBACSYS", "ORDSYS",
        "ORDDATA", "ORDPLUGINS", "GSMADMIN_INTERNAL", "DBSNMP", "OUTLN", "APPQOSSYS", "AUDSYS",
        "DVSYS", "DVF", "OJVMSYS", "ORACLE_OCM", "DBSFWUSER", "GGSYS", "REMOTE_SCHEDULER_AGENT",
        "SYS$UMF", "DIP", "ANONYMOUS", "XS$NULL", "SI_INFORMTN_SCHEMA", "EXFSYS", "FLOWS_FILES",
        "MDDATA", "SYSBACKUP", "SYSDG", "SYSKM", "SYSRAC", "WK_TEST", "WKSYS", "WKPROXY");

    @Override
    public List<Map<String, Object>> listSchemas() {
        // Mirrors the MSSQL listing contract: one row per schema with a count of
        // the objects a caller can actually browse.
        //
        // gitops#3631: the measured instance listed 12 owners, 11 of them Oracle's own
        // (SYS, MDSYS, CTXSYS, ...). A picker that offers SYS to a report author is noise,
        // so Oracle-maintained owners are dropped — by the dictionary flag where the
        // instance has it, and by the static list regardless.
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                SELECT o.OWNER AS "name", COUNT(*) AS "tableCount"
                  FROM ALL_OBJECTS o
                  LEFT JOIN ALL_USERS u ON u.USERNAME = o.OWNER
                 WHERE o.OBJECT_TYPE IN ('TABLE', 'VIEW')
                   AND NVL(u.ORACLE_MAINTAINED, 'N') <> 'Y'
                 GROUP BY o.OWNER
                 ORDER BY COUNT(*) DESC
                """, Map.of());
        } catch (org.springframework.jdbc.BadSqlGrammarException preTwelveC) {
            log.info("[{}] Oracle: ALL_USERS.ORACLE_MAINTAINED unavailable; static owner filter only", sourceId);
            rows = jdbc.queryForList("""
                SELECT OWNER AS "name", COUNT(*) AS "tableCount"
                  FROM ALL_OBJECTS
                 WHERE OBJECT_TYPE IN ('TABLE', 'VIEW')
                 GROUP BY OWNER
                 ORDER BY COUNT(*) DESC
                """, Map.of());
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null && ORACLE_OWNED_SCHEMAS.contains(name.toString().toUpperCase(java.util.Locale.ROOT))) continue;
            filtered.add(row);
        }
        return filtered;
    }

    // --------------------------------------------------------------- lineage

    @Override
    public Map<String, String> getViewDefinitions(String schema) {
        String owner = target(schema);

        // TEXT is a LONG and TEXT_VC truncates at 4000 characters, which would
        // silently hand the relationship parser a partial view for the 813
        // measured views that exceed it (largest: 76,228 chars). So TEXT it is,
        // projected LAST — a JDBC LONG must be read before no other column of its
        // row, and putting it after VIEW_NAME satisfies that.
        //
        // One query for all of them, not one query per view: measured against the
        // live IFS dictionary, the bulk read returns all 10,885 definitions in
        // 1.5s while a per-view loop averaged 13ms each — 143s for the same work.
        Map<String, String> definitions = new LinkedHashMap<>();
        jdbc.query("""
            SELECT VIEW_NAME, TEXT
              FROM ALL_VIEWS
             WHERE OWNER = :owner
             ORDER BY VIEW_NAME
            """, Map.of("owner", owner), rs -> {
                String text = rs.getString("TEXT");
                if (text != null && !text.isBlank()) {
                    definitions.put(rs.getString("VIEW_NAME"), text);
                }
            });

        log.info("[{}] Oracle: {} view definitions read for owner '{}'", sourceId, definitions.size(), owner);
        return definitions;
    }

    @Override
    public List<ForeignKeyInfo> extractForeignKeys(String schema) {
        String owner = target(schema);

        // An Oracle foreign key names the constraint it references rather than the
        // target columns, so the target side is resolved by joining back through
        // R_CONSTRAINT_NAME. Column order comes from POSITION on both sides.
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT ct.CONSTRAINT_NAME  AS fk_name,
                   ct.OWNER            AS from_owner,
                   ct.TABLE_NAME       AS from_table,
                   cc.COLUMN_NAME      AS from_column,
                   rt.OWNER            AS to_owner,
                   rt.TABLE_NAME       AS to_table,
                   rc.COLUMN_NAME      AS to_column,
                   ct.STATUS           AS status,
                   ct.VALIDATED        AS validated,
                   ct.DELETE_RULE      AS delete_rule,
                   cc.POSITION         AS position
              FROM ALL_CONSTRAINTS ct
              JOIN ALL_CONS_COLUMNS cc
                ON cc.OWNER = ct.OWNER
               AND cc.CONSTRAINT_NAME = ct.CONSTRAINT_NAME
              JOIN ALL_CONSTRAINTS rt
                ON rt.OWNER = ct.R_OWNER
               AND rt.CONSTRAINT_NAME = ct.R_CONSTRAINT_NAME
              JOIN ALL_CONS_COLUMNS rc
                ON rc.OWNER = rt.OWNER
               AND rc.CONSTRAINT_NAME = rt.CONSTRAINT_NAME
               AND rc.POSITION = cc.POSITION
             WHERE ct.CONSTRAINT_TYPE = 'R'
               AND ct.OWNER = :owner
             ORDER BY ct.CONSTRAINT_NAME, cc.POSITION
            """, Map.of("owner", owner));

        Map<String, ForeignKeyInfo> byName = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("FK_NAME");
            ForeignKeyInfo existing = byName.get(name);
            List<String> fromColumns = existing == null ? new ArrayList<>() : new ArrayList<>(existing.fromColumns());
            List<String> toColumns = existing == null ? new ArrayList<>() : new ArrayList<>(existing.toColumns());
            fromColumns.add((String) row.get("FROM_COLUMN"));
            toColumns.add((String) row.get("TO_COLUMN"));

            byName.put(name, new ForeignKeyInfo(
                name,
                (String) row.get("FROM_OWNER"),
                (String) row.get("FROM_TABLE"),
                fromColumns,
                (String) row.get("TO_OWNER"),
                (String) row.get("TO_TABLE"),
                toColumns,
                !"ENABLED".equals(row.get("STATUS")),
                !"VALIDATED".equals(row.get("VALIDATED")),
                (String) row.get("DELETE_RULE"),
                // Oracle has no ON UPDATE action; the column exists on the wire
                // contract for the MSSQL reader, so it is reported as absent
                // rather than invented.
                "NO_ACTION"
            ));
        }

        log.info("[{}] Oracle: {} foreign keys for owner '{}'", sourceId, byName.size(), owner);
        return List.copyOf(byName.values());
    }

    @Override
    public List<UniqueConstraintInfo> extractUniqueConstraints(String schema) {
        String owner = target(schema);

        // Primary keys are deliberately excluded, matching the MSSQL reader and
        // UniqueConstraintType's contract: PK is already carried by ColumnInfo.pk().
        // A declared UNIQUE constraint maps to UNIQUE_CONSTRAINT; a bare unique
        // index that backs no constraint maps to UNIQUE_INDEX.
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT ct.CONSTRAINT_NAME AS name,
                   ct.OWNER           AS owner,
                   ct.TABLE_NAME      AS table_name,
                   cc.COLUMN_NAME     AS column_name,
                   cc.POSITION        AS position
              FROM ALL_CONSTRAINTS ct
              JOIN ALL_CONS_COLUMNS cc
                ON cc.OWNER = ct.OWNER
               AND cc.CONSTRAINT_NAME = ct.CONSTRAINT_NAME
             WHERE ct.CONSTRAINT_TYPE = 'U'
               AND ct.OWNER = :owner
             ORDER BY ct.CONSTRAINT_NAME, cc.POSITION
            """, Map.of("owner", owner));

        Map<String, UniqueConstraintInfo> byName = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("NAME");
            UniqueConstraintInfo existing = byName.get(name);
            List<String> columns = existing == null ? new ArrayList<>() : new ArrayList<>(existing.columns());
            columns.add((String) row.get("COLUMN_NAME"));

            byName.put(name, new UniqueConstraintInfo(
                name,
                (String) row.get("OWNER"),
                (String) row.get("TABLE_NAME"),
                columns,
                UniqueConstraintType.UNIQUE_CONSTRAINT,
                null                                  // Oracle has no filtered unique constraint
            ));
        }
        return List.copyOf(byName.values());
    }

    @Override
    public List<CheckConstraintInfo> extractCheckConstraints(String schema) {
        String owner = target(schema);

        // SEARCH_CONDITION is LONG; SEARCH_CONDITION_VC (12c+) carries the same
        // predicate as VARCHAR2 and check predicates are short, so the VC twin is
        // the right trade here — unlike view text, which routinely exceeds it.
        return jdbc.query("""
            SELECT ct.CONSTRAINT_NAME     AS name,
                   ct.OWNER               AS owner,
                   ct.TABLE_NAME          AS table_name,
                   ct.SEARCH_CONDITION_VC AS definition,
                   ct.STATUS              AS status,
                   ct.VALIDATED           AS validated
              FROM ALL_CONSTRAINTS ct
             WHERE ct.CONSTRAINT_TYPE = 'C'
               AND ct.OWNER = :owner
             ORDER BY ct.CONSTRAINT_NAME
            """, Map.of("owner", owner), (rs, rowNum) -> new CheckConstraintInfo(
                rs.getString("name"),
                rs.getString("owner"),
                rs.getString("table_name"),
                null,                                  // column binding is not exposed by ALL_CONSTRAINTS
                rs.getString("definition"),
                !"ENABLED".equals(rs.getString("status")),
                !"VALIDATED".equals(rs.getString("validated"))
        ));
    }

    @Override
    public List<DefaultConstraintInfo> extractDefaultConstraints(String schema) {
        String owner = target(schema);

        // DATA_DEFAULT is LONG, so it is the only projected column alongside the
        // identifiers needed to place it; DEFAULT_LENGTH filters out the columns
        // that have no default at all before the LONG is touched.
        return jdbc.query("""
            SELECT TABLE_NAME, COLUMN_NAME, DATA_DEFAULT
              FROM ALL_TAB_COLUMNS
             WHERE OWNER = :owner
               AND DEFAULT_LENGTH IS NOT NULL
             ORDER BY TABLE_NAME, COLUMN_ID
            """, Map.of("owner", owner), (rs, rowNum) -> new DefaultConstraintInfo(
                // Oracle column defaults are unnamed; a synthetic, stable name keeps
                // the wire contract's name field meaningful instead of null.
                "DEFAULT_" + rs.getString("TABLE_NAME") + "_" + rs.getString("COLUMN_NAME"),
                owner,
                rs.getString("TABLE_NAME"),
                rs.getString("COLUMN_NAME"),
                rs.getString("DATA_DEFAULT")
        ));
    }

    @Override
    public List<IndexInfo> extractIndexes(String schema) {
        String owner = target(schema);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT i.INDEX_NAME   AS name,
                   i.TABLE_OWNER  AS owner,
                   i.TABLE_NAME   AS table_name,
                   i.INDEX_TYPE   AS index_type,
                   i.UNIQUENESS   AS uniqueness,
                   i.STATUS       AS status,
                   ic.COLUMN_NAME AS column_name,
                   ic.COLUMN_POSITION AS position,
                   ic.DESCEND     AS descend
              FROM ALL_INDEXES i
              JOIN ALL_IND_COLUMNS ic
                ON ic.INDEX_OWNER = i.OWNER
               AND ic.INDEX_NAME = i.INDEX_NAME
             WHERE i.TABLE_OWNER = :owner
             ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION
            """, Map.of("owner", owner));

        Map<String, IndexInfo> byName = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("NAME");
            IndexInfo existing = byName.get(name);
            List<IndexInfo.KeyColumn> keys = existing == null
                ? new ArrayList<>()
                : new ArrayList<>(existing.keyColumns());
            keys.add(new IndexInfo.KeyColumn(
                (String) row.get("COLUMN_NAME"),
                ((Number) row.get("POSITION")).intValue(),
                "DESC".equals(row.get("DESCEND"))
            ));

            byName.put(name, new IndexInfo(
                name,
                (String) row.get("OWNER"),
                (String) row.get("TABLE_NAME"),
                (String) row.get("INDEX_TYPE"),
                keys,
                List.of(),                              // Oracle has no INCLUDE columns
                "UNIQUE".equals(row.get("UNIQUENESS")),
                false,                                  // primary-key flag lives on the constraint
                false,
                false,                                  // no filtered indexes
                null,
                0,                                      // fill factor has no Oracle equivalent
                !"VALID".equals(row.get("STATUS")),
                false
            ));
        }
        return List.copyOf(byName.values());
    }

    @Override
    public List<ObjectInfo> extractObjects(String schema) {
        String owner = target(schema);
        return jdbc.query("""
            SELECT OBJECT_NAME, OWNER, OBJECT_TYPE, OBJECT_ID, CREATED, LAST_DDL_TIME
              FROM ALL_OBJECTS
             WHERE OWNER = :owner
             ORDER BY OBJECT_TYPE, OBJECT_NAME
            """, Map.of("owner", owner), (rs, rowNum) -> {
                Timestamp created = rs.getTimestamp("CREATED");
                Timestamp modified = rs.getTimestamp("LAST_DDL_TIME");
                Number id = (Number) rs.getObject("OBJECT_ID");
                return new ObjectInfo(
                    rs.getString("OBJECT_NAME"),
                    rs.getString("OWNER"),
                    rs.getString("OBJECT_TYPE"),
                    id == null ? null : id.intValue(),
                    rs.getString("OWNER"),
                    created == null ? null : created.toLocalDateTime(),
                    modified == null ? null : modified.toLocalDateTime(),
                    Map.of()                            // extended properties are an MSSQL concept
                );
            });
    }

    @Override
    public Map<String, Long> getRowCounts(String schema) {
        String owner = target(schema);

        // NUM_ROWS is optimizer statistics, not a live count: it is null until the
        // schema is analysed and stale afterwards. Counting 10,885 views for real
        // would mean executing every one of them, so the statistic is reported as
        // the approximation it is and views simply carry none.
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
            SELECT TABLE_NAME, NUM_ROWS
              FROM ALL_TABLES
             WHERE OWNER = :owner
               AND NUM_ROWS IS NOT NULL
            """, Map.of("owner", owner), rs -> {
                // A block body, not an expression: Map.put returns a value, which
                // makes the call ambiguous between RowCallbackHandler and
                // ResultSetExtractor.
                counts.put(rs.getString("TABLE_NAME"), rs.getLong("NUM_ROWS"));
            });
        return counts;
    }

    @Override
    public List<StorageInfo> extractStorage(String schema) {
        String owner = target(schema);

        // ALL_TABLES exposes blocks, not the MSSQL breakdown of reserved/used/
        // data/index/lob bytes. Only the two fields with a faithful equivalent are
        // populated; the rest stay zero rather than being back-computed into
        // numbers that would read as measurements.
        return jdbc.query("""
            SELECT TABLE_NAME, NUM_ROWS, BLOCKS
              FROM ALL_TABLES
             WHERE OWNER = :owner
            """, Map.of("owner", owner), (rs, rowNum) -> {
                long blocks = rs.getLong("BLOCKS");
                return new StorageInfo(
                    rs.getString("TABLE_NAME"),
                    owner,
                    rs.getLong("NUM_ROWS"),
                    blocks * 8,                         // default 8 KiB block
                    blocks * 8,
                    blocks * 8,
                    0, 0, 0
                );
            });
    }

    @Override
    public List<ChangeDataInfo> extractChangeData(String schema) {
        // CDC, Change Tracking, temporal tables and replication flags are the
        // MSSQL feature set this record models. Oracle's counterparts (Flashback
        // Data Archive, GoldenGate) do not map onto these fields, so an empty
        // inventory is the honest answer rather than a partial mapping that would
        // read as "no features enabled".
        log.debug("[{}] Oracle: change-data inventory has no faithful equivalent — reporting empty", sourceId);
        return List.of();
    }

    @Override
    public DatabaseOptionsInfo extractDatabaseOptions() {
        // Every field of this record is MSSQL-specific (recovery model, snapshot
        // isolation state, page verify, ANSI padding). Null is the documented
        // "no equivalent" value; the snapshot builder already tolerates it.
        return null;
    }
}
