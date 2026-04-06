package com.example.report.contexthealth;

import com.example.report.dto.ChartResultDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ContextHealthChartService {

    private final ContextHealthFileReader reader;

    public ContextHealthChartService(ContextHealthFileReader reader) {
        this.reader = reader;
    }

    @Cacheable("contextHealthCharts")
    public List<ChartResultDto> computeCharts() {
        var status = reader.readSystemStatus().orElse(null);
        List<ChartResultDto> charts = new ArrayList<>();
        charts.add(computeHealthComponentBreakdown(status));
        charts.add(computeSectionStatusDistribution(status));
        charts.add(computeDocGraphMetrics(status));
        charts.add(computeProjectDistribution(status));
        charts.add(computeProviderCapabilities(status));
        charts.add(computeWorkIntakeBreakdown(status));
        charts.add(computeBenchmarkGaps(status));
        charts.add(computeLayerBoundary(status));
        charts.add(computeRepoHygiene(status));
        return charts;
    }

    private ChartResultDto computeHealthComponentBreakdown(JsonNode status) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (status != null) {
            var components = jsonPath(status, "sections", "context_health", "components");
            if (components != null && components.isObject()) {
                var it = components.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    String label = formatLabel(entry.getKey());
                    int score = entry.getValue().path("score").asInt(0);
                    int max = entry.getValue().path("max").asInt(20);
                    data.add(Map.of("label", label, "value", score, "max", max));
                }
            }
        }
        return new ChartResultDto(
                "health-component-breakdown",
                "Health Score Components",
                "bar",
                "lg",
                data,
                Map.of("orientation", "horizontal", "showValues", true),
                null
        );
    }

    private ChartResultDto computeSectionStatusDistribution(JsonNode status) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("OK", 0);
        counts.put("WARN", 0);
        counts.put("FAIL", 0);
        counts.put("IDLE", 0);
        if (status != null) {
            var sections = status.get("sections");
            if (sections != null && sections.isObject()) {
                var it = sections.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    var sectionStatus = entry.getValue().path("status").asText("IDLE");
                    String bucket = switch (sectionStatus) {
                        case "OK", "READY" -> "OK";
                        case "WARN", "NOT_READY" -> "WARN";
                        case "FAIL" -> "FAIL";
                        default -> "IDLE";
                    };
                    counts.merge(bucket, 1, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> data = counts.entrySet().stream()
                .map(e -> Map.<String, Object>of("label", e.getKey(), "value", e.getValue()))
                .toList();
        return new ChartResultDto(
                "section-status-distribution",
                "Section Status Distribution",
                "pie",
                "md",
                data,
                Map.of("showLegend", true, "showPercentage", true),
                null
        );
    }

    private ChartResultDto computeDocGraphMetrics(JsonNode status) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (status != null) {
            var docGraph = jsonPath(status, "sections", "doc_graph");
            if (docGraph != null) {
                data.add(Map.of("label", "Broken Refs", "value", docGraph.path("broken_refs").asInt(0)));
                data.add(Map.of("label", "Orphan Critical", "value", docGraph.path("orphan_critical").asInt(0)));
                data.add(Map.of("label", "Placeholder Refs", "value", docGraph.path("placeholder_refs_count").asInt(0)));
                data.add(Map.of("label", "Ambiguity", "value", docGraph.path("ambiguity").asInt(0)));
            }
        }
        return new ChartResultDto(
                "doc-graph-metrics",
                "Doc Graph Issues",
                "bar",
                "md",
                data,
                Map.of("showValues", true),
                null
        );
    }

    private ChartResultDto computeProjectDistribution(JsonNode status) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (status != null) {
            var projects = jsonPath(status, "sections", "projects");
            if (projects != null) {
                int active = projects.path("projects_count").asInt(0);
                data.add(Map.of("label", "Active Projects", "value", active));
            }
            var extensions = jsonPath(status, "sections", "extensions");
            if (extensions != null) {
                int total = extensions.path("count_total").asInt(0);
                int enabled = extensions.path("enabled_count").asInt(0);
                data.add(Map.of("label", "Extensions (Total)", "value", total));
                data.add(Map.of("label", "Extensions (Enabled)", "value", enabled));
            }
            var providers = jsonPath(status, "sections", "provider_capability");
            if (providers != null) {
                data.add(Map.of("label", "AI Providers", "value", providers.path("provider_count").asInt(0)));
                data.add(Map.of("label", "Capabilities", "value", providers.path("capability_count").asInt(0)));
            }
        }
        return new ChartResultDto(
                "project-distribution",
                "Project & Extension Overview",
                "bar",
                "md",
                data,
                Map.of("showValues", true),
                null
        );
    }

    private ChartResultDto computeProviderCapabilities(JsonNode status) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (status != null) {
            var providers = jsonPath(status, "sections", "provider_capability", "providers");
            if (providers != null && providers.isArray()) {
                for (var provider : providers) {
                    String name = provider.path("provider").asText("unknown");
                    int supported = provider.path("supported_count").asInt(0);
                    int experimental = provider.path("experimental_count").asInt(0);
                    data.add(Map.of(
                            "label", name,
                            "value", supported + experimental,
                            "supported", supported,
                            "experimental", experimental
                    ));
                }
            }
        }
        return new ChartResultDto(
                "provider-capabilities",
                "AI Provider Capabilities",
                "bar",
                "md",
                data,
                Map.of("showValues", true, "orientation", "horizontal"),
                null
        );
    }

    private ChartResultDto computeWorkIntakeBreakdown(JsonNode status) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (status != null) {
            var wi = jsonPath(status, "sections", "work_intake");
            if (wi != null) {
                data.add(Map.of("label", "Open", "value", wi.path("open_count").asInt(0)));
                data.add(Map.of("label", "Planned", "value", wi.path("planned_count").asInt(0)));
                data.add(Map.of("label", "In Progress", "value", wi.path("in_progress_count").asInt(0)));
                data.add(Map.of("label", "Done", "value", wi.path("done_count").asInt(0)));
            }
            var exec = jsonPath(status, "sections", "work_intake_exec");
            if (exec != null) {
                data.add(Map.of("label", "Exec Open", "value", exec.path("open_count").asInt(0)));
                data.add(Map.of("label", "Exec Done", "value", exec.path("done_count").asInt(0)));
            }
        }
        return new ChartResultDto("work-intake-breakdown", "Work Intake Pipeline", "bar", "md", data, Map.of("showValues", true), null);
    }

    private ChartResultDto computeBenchmarkGaps(JsonNode status) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (status != null) {
            var bench = jsonPath(status, "sections", "benchmark");
            if (bench != null) {
                var gaps = bench.path("gaps_by_severity");
                if (gaps != null && gaps.isObject()) {
                    data.add(Map.of("label", "High", "value", gaps.path("high").asInt(0)));
                    data.add(Map.of("label", "Medium", "value", gaps.path("medium").asInt(0)));
                    data.add(Map.of("label", "Low", "value", gaps.path("low").asInt(0)));
                }
                data.add(Map.of("label", "Controls", "value", bench.path("controls_count").asInt(0)));
                data.add(Map.of("label", "Metrics", "value", bench.path("metrics_count").asInt(0)));
            }
        }
        return new ChartResultDto("benchmark-gaps", "Benchmark & Gaps", "bar", "md", data, Map.of("showValues", true), null);
    }

    private ChartResultDto computeLayerBoundary(JsonNode status) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (status != null) {
            var lb = jsonPath(status, "sections", "layer_boundary");
            if (lb != null) {
                data.add(Map.of("label", "Would Block", "value", lb.path("would_block_count").asInt(0)));
                data.add(Map.of("label", "Allowed", "value", lb.path("allowed_count").asInt(0)));
                data.add(Map.of("label", "Scanned", "value", lb.path("scanned_count").asInt(0)));
            }
            var deploy = jsonPath(status, "sections", "deploy");
            if (deploy != null) {
                String deployStatus = deploy.path("status").asText("IDLE");
                data.add(Map.of("label", "Deploy (" + deployStatus + ")", "value", deploy.path("deploy_targets").path("environments_count").asInt(0)));
            }
        }
        return new ChartResultDto("layer-boundary", "Layer Boundary & Deploy", "bar", "md", data, Map.of("showValues", true), null);
    }

    private ChartResultDto computeRepoHygiene(JsonNode status) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (status != null) {
            var rh = jsonPath(status, "sections", "repo_hygiene");
            if (rh != null) {
                data.add(Map.of("label", "Unexpected Dirs", "value", rh.path("unexpected_top_level_dirs").asInt(0)));
                data.add(Map.of("label", "Tracked Generated", "value", rh.path("tracked_generated_files").asInt(0)));
                var findings = rh.path("top_findings");
                int warnCount = 0, infoCount = 0;
                if (findings != null && findings.isArray()) {
                    for (var f : findings) {
                        String sev = f.path("severity").asText("");
                        if ("WARN".equals(sev)) warnCount++;
                        else infoCount++;
                    }
                }
                data.add(Map.of("label", "Warnings", "value", warnCount));
                data.add(Map.of("label", "Info", "value", infoCount));
            }
        }
        return new ChartResultDto("repo-hygiene", "Repo Hygiene", "bar", "md", data, Map.of("showValues", true), null);
    }

    private String formatLabel(String key) {
        return key.replace("_", " ").substring(0, 1).toUpperCase() + key.replace("_", " ").substring(1);
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
