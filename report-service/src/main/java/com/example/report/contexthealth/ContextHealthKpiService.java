package com.example.report.contexthealth;

import com.example.report.dto.KpiResultDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ContextHealthKpiService {

    private final ContextHealthFileReader reader;

    public ContextHealthKpiService(ContextHealthFileReader reader) {
        this.reader = reader;
    }

    @Cacheable("contextHealthKpis")
    public List<KpiResultDto> computeKpis() {
        var status = reader.readSystemStatus().orElse(null);
        List<KpiResultDto> kpis = new ArrayList<>();
        // Row 1: Core health
        kpis.add(computeBootstrapGate(status));
        kpis.add(computeHealthScore(status));
        kpis.add(computeQualityTrend(status));
        // Row 2: Context quality
        kpis.add(computeCacheHitRate(status));
        kpis.add(computeRuleEffectiveness(status));
        kpis.add(computeDocGraphHealth(status));
        // Row 3: Operations
        kpis.add(computeExtensionCount(status));
        kpis.add(computeDecisionsPending(status));
        kpis.add(computeWorkIntakeOpen(status));
        // Row 4: Integrity & drift
        kpis.add(computeBenchmarkMaturity(status));
        kpis.add(computeDriftStatus(status));
        kpis.add(computeGitIntegrity(status));
        return kpis;
    }

    private KpiResultDto computeBootstrapGate(JsonNode status) {
        String value = "N/A";
        String tone = "default";
        if (status != null) {
            var readiness = jsonPath(status, "sections", "readiness", "status");
            if (readiness != null) {
                value = readiness.asText("N/A");
                tone = switch (value) {
                    case "READY" -> "success";
                    case "NOT_READY" -> "danger";
                    default -> value.contains("WARN") ? "warning" : "danger";
                };
                value = value.equals("READY") ? "PASS" : value.equals("NOT_READY") ? "BLOCKED" : "WARN";
            }
        }
        return new KpiResultDto("bootstrap-gate", "Bootstrap Gate", "text", value, value, null, tone, null, null);
    }

    private KpiResultDto computeHealthScore(JsonNode status) {
        int score = 0;
        String tone = "danger";
        if (status != null) {
            var scoreNode = jsonPath(status, "sections", "context_health", "score");
            if (scoreNode != null) {
                score = scoreNode.asInt(0);
                tone = score >= 80 ? "success" : score >= 50 ? "warning" : "danger";
            }
        }
        return new KpiResultDto("health-score", "Health Score", "number", score, score + "/100", null, tone, null, null);
    }

    private KpiResultDto computeQualityTrend(JsonNode status) {
        String trend = "STABLE";
        String tone = "info";
        if (status != null) {
            var qg = jsonPath(status, "sections", "quality_gate", "status");
            var bench = jsonPath(status, "sections", "benchmark", "status");
            if (bench != null && "FAIL".equals(bench.asText())) {
                trend = "DEGRADING";
                tone = "danger";
            } else if (qg != null && "OK".equals(qg.asText())) {
                trend = "STABLE";
                tone = "info";
            }
        }
        return new KpiResultDto("quality-trend", "Quality Trend", "text", trend, trend, null, tone, null, null);
    }

    private KpiResultDto computeCacheHitRate(JsonNode status) {
        double rate = 0.0;
        String tone = "danger";
        if (status != null) {
            var comp = jsonPath(status, "sections", "context_health", "components", "cache_hit_rate");
            if (comp != null) {
                int score = comp.path("score").asInt(0);
                int max = comp.path("max").asInt(20);
                rate = max > 0 ? (double) score / max * 100 : 0;
                tone = rate >= 90 ? "success" : rate >= 70 ? "warning" : "danger";
            }
        }
        return new KpiResultDto("cache-hit-rate", "Cache Hit Rate", "percent", rate, String.format("%.0f%%", rate), null, tone, null, null);
    }

    private KpiResultDto computeRuleEffectiveness(JsonNode status) {
        double pct = 0.0;
        String tone = "danger";
        if (status != null) {
            var comp = jsonPath(status, "sections", "context_health", "components", "rule_relevance");
            if (comp != null) {
                int score = comp.path("score").asInt(0);
                int max = comp.path("max").asInt(20);
                pct = max > 0 ? (double) score / max * 100 : 0;
                tone = pct >= 70 ? "success" : pct >= 40 ? "warning" : "danger";
            }
        }
        return new KpiResultDto("rule-effectiveness", "Rule Effectiveness", "percent", pct, String.format("%.0f%%", pct), null, tone, null, null);
    }

    private KpiResultDto computeDocGraphHealth(JsonNode status) {
        int broken = 0;
        String tone = "success";
        if (status != null) {
            var dg = jsonPath(status, "sections", "doc_graph");
            if (dg != null) {
                broken = dg.path("broken_refs").asInt(0);
                int orphan = dg.path("orphan_critical").asInt(0);
                broken += orphan;
                tone = broken == 0 ? "success" : broken <= 10 ? "warning" : "danger";
            }
        }
        return new KpiResultDto("doc-graph-health", "Doc Graph Issues", "number", broken, String.valueOf(broken), null, tone, null, null);
    }

    private KpiResultDto computeExtensionCount(JsonNode status) {
        int total = 0;
        int enabled = 0;
        if (status != null) {
            var ext = jsonPath(status, "sections", "extensions");
            if (ext != null) {
                total = ext.path("count_total").asInt(0);
                enabled = ext.path("enabled_count").asInt(0);
            }
        }
        String formatted = enabled + "/" + total;
        String tone = total > 0 ? "info" : "default";
        return new KpiResultDto("extensions", "Extensions", "text", enabled, formatted + " active", null, tone, null, null);
    }

    private KpiResultDto computeDecisionsPending(JsonNode status) {
        int pending = 0;
        String tone = "success";
        if (status != null) {
            var dec = jsonPath(status, "sections", "decisions");
            if (dec != null) {
                pending = dec.path("pending_decisions_count").asInt(0);
                tone = pending == 0 ? "success" : pending <= 3 ? "warning" : "danger";
            }
        }
        return new KpiResultDto("decisions-pending", "Decisions Pending", "number", pending, String.valueOf(pending), null, tone, null, null);
    }

    private KpiResultDto computeWorkIntakeOpen(JsonNode status) {
        int open = 0;
        String tone = "info";
        if (status != null) {
            var wi = jsonPath(status, "sections", "work_intake");
            if (wi != null) {
                open = wi.path("open_count").asInt(0);
                tone = open == 0 ? "success" : open <= 5 ? "info" : "warning";
            }
        }
        return new KpiResultDto("work-intake-open", "Work Intake Open", "number", open, String.valueOf(open), null, tone, null, null);
    }

    private KpiResultDto computeBenchmarkMaturity(JsonNode status) {
        double avg = 0.0;
        String tone = "danger";
        if (status != null) {
            var bench = jsonPath(status, "sections", "benchmark");
            if (bench != null) {
                avg = bench.path("maturity_avg").asDouble(0.0);
                tone = avg >= 0.75 ? "success" : avg >= 0.5 ? "warning" : "danger";
            }
        }
        String formatted = String.format("%.0f%%", avg * 100);
        return new KpiResultDto("benchmark-maturity", "Maturity", "percent", avg * 100, formatted, null, tone, null, null);
    }

    private KpiResultDto computeDriftStatus(JsonNode status) {
        String value = "IDLE";
        String tone = "default";
        if (status != null) {
            var drift = jsonPath(status, "sections", "drift_scoreboard");
            if (drift != null) {
                int failed = drift.path("drift_failed_count").asInt(0);
                int pending = drift.path("drift_pending_count").asInt(0);
                if (failed > 0) { value = failed + " FAIL"; tone = "danger"; }
                else if (pending > 0) { value = pending + " pending"; tone = "warning"; }
                else { value = "Clean"; tone = "success"; }
            }
        }
        return new KpiResultDto("drift-status", "Drift", "text", value, value, null, tone, null, null);
    }

    private KpiResultDto computeGitIntegrity(JsonNode status) {
        int dirty = 0;
        String tone = "success";
        if (status != null) {
            var ci = jsonPath(status, "sections", "core_integrity");
            if (ci != null) {
                dirty = ci.path("dirty_files_count").asInt(0);
                boolean clean = ci.path("git_clean").asBoolean(false);
                tone = clean ? "success" : dirty <= 5 ? "warning" : "danger";
            }
        }
        String formatted = dirty == 0 ? "Clean" : dirty + " dirty";
        return new KpiResultDto("git-integrity", "Git Integrity", "text", dirty, formatted, null, tone, null, null);
    }

    private JsonNode jsonPath(JsonNode root, String... keys) {
        JsonNode current = root;
        for (String key : keys) {
            if (current == null || current.isMissingNode()) return null;
            current = current.get(key);
        }
        return current;
    }
}
