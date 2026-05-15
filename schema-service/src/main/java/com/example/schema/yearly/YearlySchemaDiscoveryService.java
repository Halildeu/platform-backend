package com.example.schema.yearly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Phase 2 Program 2b — Yearly schema discovery.
 *
 * <p>Codex iter-15 §2b-AGREE absorb (thread 019e0119): MSSQL
 * {@code sys.schemas} discovery + Java regex normalization.
 * SQL {@code LIKE} alone is insufficient (underscore wildcard +
 * similar-named schemas); regex parses the canonical
 * {@code workcube_mikrolink_<year>_<companyId>} pattern.
 *
 * <p>Phase 1 portability (Codex 019e2d14 §8 — quick win 2/5): the
 * SQL {@code LIKE} pre-filter and the Java regex are config-driven
 * ({@code schema.yearly.like-pattern} / {@code schema.yearly.regex}).
 * Defaults stay Workcube-compatible; a new ERP overrides them without
 * code changes.
 *
 * <p><strong>Regex contract:</strong> the configured regex MUST expose
 * at least two capture groups — {@code group(1)} a numeric year,
 * {@code group(2)} the companyId. An invalid regex (syntax error or
 * fewer than two groups) falls back to the Workcube default with a
 * warning rather than failing startup. A custom regex whose year group
 * yields a non-numeric value causes that individual schema to be
 * skipped (logged), never an exception.
 *
 * <p>Build-time/CLI use only — exposed as Spring Service so the
 * {@code YearlySchemaCoverageExporter} runner can invoke it; no
 * REST endpoint surface.
 */
@Service
public class YearlySchemaDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(YearlySchemaDiscoveryService.class);

    /** Workcube-compatible default SQL {@code LIKE} pre-filter. */
    static final String DEFAULT_LIKE_PATTERN = "workcube_mikrolink_%_%";

    /**
     * Workcube-compatible default regex:
     * {@code workcube_mikrolink_<4-digit-year>_<digits-companyId>}.
     */
    static final String DEFAULT_REGEX = "^workcube_mikrolink_(\\d{4})_(\\d+)$";

    /** SQL skeleton — the {@code LIKE} pattern is bound as a parameter. */
    static final String DISCOVERY_SQL = "SELECT name FROM sys.schemas WHERE name LIKE ?";

    private final JdbcTemplate jdbc;
    private final String likePattern;
    private final Pattern yearlyPattern;

    @Autowired
    public YearlySchemaDiscoveryService(
            JdbcTemplate jdbc,
            @Value("${schema.yearly.like-pattern:}") String likePattern,
            @Value("${schema.yearly.regex:}") String regex) {
        this.jdbc = jdbc;
        this.likePattern = (likePattern == null || likePattern.isBlank())
                ? DEFAULT_LIKE_PATTERN
                : likePattern.trim();
        this.yearlyPattern = compileSafe(regex);
        log.info("YearlySchemaDiscoveryService initialized: likePattern='{}' regex='{}'",
                this.likePattern, this.yearlyPattern.pattern());
    }

    /**
     * Compile the configured regex, falling back to the Workcube default
     * when the value is blank, syntactically invalid, or exposes fewer
     * than two capture groups (year + companyId contract).
     */
    private static Pattern compileSafe(String regex) {
        if (regex == null || regex.isBlank()) {
            return Pattern.compile(DEFAULT_REGEX);
        }
        String trimmed = regex.trim();
        try {
            Pattern p = Pattern.compile(trimmed);
            int groups = p.matcher("").groupCount();
            if (groups < 2) {
                log.warn("schema.yearly.regex '{}' exposes {} capture group(s); "
                        + "contract requires >= 2 (year, companyId). "
                        + "Falling back to Workcube default.", trimmed, groups);
                return Pattern.compile(DEFAULT_REGEX);
            }
            return p;
        } catch (PatternSyntaxException e) {
            log.warn("schema.yearly.regex '{}' is invalid: {}. "
                    + "Falling back to Workcube default.", trimmed, e.getMessage());
            return Pattern.compile(DEFAULT_REGEX);
        }
    }

    /**
     * Discover all yearly partition schemas matching the configured pattern.
     *
     * @param yearFilter      optional whitelist of years (empty → all)
     * @param companyFilter   optional whitelist of companyIds (empty → all)
     * @return deterministic-ordered list (year asc, companyId asc)
     */
    public List<DiscoveredSchema> discover(List<Integer> yearFilter, List<String> companyFilter) {
        List<String> candidates = jdbc.queryForList(DISCOVERY_SQL, String.class, likePattern);
        log.debug("Yearly schema discovery candidates: {}", candidates.size());

        List<DiscoveredSchema> matched = new ArrayList<>();
        for (String name : candidates) {
            Matcher m = yearlyPattern.matcher(name);
            if (!m.matches()) {
                continue;
            }
            int year;
            try {
                year = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                log.warn("Schema '{}' matched the regex but year group '{}' is not numeric; "
                        + "skipped.", name, m.group(1));
                continue;
            }
            String companyId = m.group(2);

            if (!yearFilter.isEmpty() && !yearFilter.contains(year)) {
                continue;
            }
            if (!companyFilter.isEmpty() && !companyFilter.contains(companyId)) {
                continue;
            }
            matched.add(new DiscoveredSchema(name, year, companyId));
        }

        matched.sort(Comparator
                .comparingInt(DiscoveredSchema::year)
                .thenComparing(DiscoveredSchema::companyId));
        log.info("Yearly schema discovery matched {} schemas (years={} companies={})",
                matched.size(), yearFilter, companyFilter);
        return Collections.unmodifiableList(matched);
    }

    /** Discovered schema record (year + companyId parsed from name). */
    public record DiscoveredSchema(String name, int year, String companyId) {}
}
