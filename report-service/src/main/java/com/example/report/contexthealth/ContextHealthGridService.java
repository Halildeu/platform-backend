package com.example.report.contexthealth;

import com.example.report.contexthealth.dto.ContextHealthGridMetaDto;
import com.example.report.contexthealth.dto.ContextHealthGridMetaDto.ColumnDef;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ContextHealthGridService {

    private final ContextHealthFileReader reader;

    public ContextHealthGridService(ContextHealthFileReader reader) {
        this.reader = reader;
    }

    public List<ContextHealthGridMetaDto> listGrids() {
        return List.of(
                new ContextHealthGridMetaDto("active-projects", "Active Projects", List.of(
                        new ColumnDef("projectId", "Project ID", "text", 200),
                        new ColumnDef("title", "Title", "text", 280),
                        new ColumnDef("owner", "Owner", "text", 100),
                        new ColumnDef("layerScope", "Layer", "badge", 80),
                        new ColumnDef("riskCategory", "Risk", "badge", 100)
                )),
                new ContextHealthGridMetaDto("system-sections", "System Sections", List.of(
                        new ColumnDef("section", "Section", "text", 200),
                        new ColumnDef("status", "Status", "badge", 100),
                        new ColumnDef("notes", "Notes", "text", 400)
                )),
                new ContextHealthGridMetaDto("health-components", "Health Components", List.of(
                        new ColumnDef("component", "Component", "text", 200),
                        new ColumnDef("score", "Score", "number", 80),
                        new ColumnDef("max", "Max", "number", 80),
                        new ColumnDef("percentage", "Percentage", "number", 100)
                )),
                new ContextHealthGridMetaDto("doc-graph-broken", "Doc Graph — Broken Refs", List.of(
                        new ColumnDef("source", "Source", "text", 300),
                        new ColumnDef("target", "Target", "text", 300),
                        new ColumnDef("kind", "Kind", "badge", 120)
                )),
                new ContextHealthGridMetaDto("provider-details", "AI Providers", List.of(
                        new ColumnDef("provider", "Provider", "text", 120),
                        new ColumnDef("defaultModel", "Default Model", "text", 250),
                        new ColumnDef("enabled", "Enabled", "badge", 80),
                        new ColumnDef("supportedCount", "Supported", "number", 100),
                        new ColumnDef("experimentalCount", "Experimental", "number", 100)
                )),
                new ContextHealthGridMetaDto("doc-graph-orphans", "Doc Graph — Orphans", List.of(
                        new ColumnDef("path", "Path", "text", 400),
                        new ColumnDef("reason", "Reason", "badge", 250)
                )),
                new ContextHealthGridMetaDto("repo-hygiene", "Repo Hygiene", List.of(
                        new ColumnDef("kind", "Kind", "badge", 150),
                        new ColumnDef("path", "Path", "text", 300),
                        new ColumnDef("severity", "Severity", "badge", 100)
                )),
                new ContextHealthGridMetaDto("cockpit-state", "Cockpit State", List.of(
                        new ColumnDef("field", "Field", "text", 250),
                        new ColumnDef("value", "Value", "text", 350)
                ))
        );
    }

    @Cacheable(value = "contextHealthGrids", key = "#gridId")
    public List<Map<String, Object>> getGridData(String gridId) {
        return switch (gridId) {
            case "active-projects" -> getActiveProjects();
            case "system-sections" -> getSystemSections();
            case "health-components" -> getHealthComponents();
            case "doc-graph-broken" -> getDocGraphBroken();
            case "doc-graph-orphans" -> getDocGraphOrphans();
            case "provider-details" -> getProviderDetails();
            case "repo-hygiene" -> getRepoHygiene();
            case "cockpit-state" -> getCockpitState();
            default -> List.of();
        };
    }

    private List<Map<String, Object>> getActiveProjects() {
        var portfolio = reader.readPortfolioStatus().orElse(null);
        if (portfolio == null || !portfolio.has("projects")) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var project : portfolio.get("projects")) {
            rows.add(Map.of(
                    "projectId", project.path("project_id").asText(""),
                    "title", project.path("title").asText(""),
                    "owner", "CORE",
                    "layerScope", project.path("version").asText(""),
                    "riskCategory", "—"
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> getSystemSections() {
        var status = reader.readSystemStatus().orElse(null);
        if (status == null || !status.has("sections")) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        var sections = status.get("sections");
        var it = sections.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String sectionStatus = entry.getValue().path("status").asText("—");
            var notesNode = entry.getValue().get("notes");
            String notes = "";
            if (notesNode != null && notesNode.isArray() && !notesNode.isEmpty()) {
                var sb = new StringBuilder();
                for (int i = 0; i < Math.min(notesNode.size(), 3); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(notesNode.get(i).asText());
                }
                if (notesNode.size() > 3) sb.append(" (+").append(notesNode.size() - 3).append(" more)");
                notes = sb.toString();
            }
            rows.add(Map.of(
                    "section", entry.getKey(),
                    "status", sectionStatus,
                    "notes", notes
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> getHealthComponents() {
        var status = reader.readSystemStatus().orElse(null);
        if (status == null) return List.of();
        var components = jsonPath(status, "sections", "context_health", "components");
        if (components == null || !components.isObject()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        var it = components.fields();
        while (it.hasNext()) {
            var entry = it.next();
            int score = entry.getValue().path("score").asInt(0);
            int max = entry.getValue().path("max").asInt(20);
            double pct = max > 0 ? (double) score / max * 100 : 0;
            rows.add(Map.of(
                    "component", entry.getKey().replace("_", " "),
                    "score", score,
                    "max", max,
                    "percentage", Math.round(pct)
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> getDocGraphBroken() {
        var status = reader.readSystemStatus().orElse(null);
        if (status == null) return List.of();
        var topBroken = jsonPath(status, "sections", "doc_graph", "top_broken");
        if (topBroken == null || !topBroken.isArray()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var item : topBroken) {
            rows.add(Map.of(
                    "source", item.path("source").asText(""),
                    "target", item.path("target").asText(""),
                    "kind", item.path("kind").asText("")
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> getProviderDetails() {
        var status = reader.readSystemStatus().orElse(null);
        if (status == null) return List.of();
        var providers = jsonPath(status, "sections", "provider_capability", "providers");
        if (providers == null || !providers.isArray()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var provider : providers) {
            rows.add(Map.of(
                    "provider", provider.path("provider").asText(""),
                    "defaultModel", provider.path("default_model").asText(""),
                    "enabled", provider.path("enabled").asBoolean(false) ? "Yes" : "No",
                    "supportedCount", provider.path("supported_count").asInt(0),
                    "experimentalCount", provider.path("experimental_count").asInt(0)
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> getDocGraphOrphans() {
        var status = reader.readSystemStatus().orElse(null);
        if (status == null) return List.of();
        var topOrphan = jsonPath(status, "sections", "doc_graph", "top_orphan");
        if (topOrphan == null || !topOrphan.isArray()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var item : topOrphan) {
            rows.add(Map.of(
                    "path", item.path("path").asText(""),
                    "reason", item.path("reason").asText("")
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> getRepoHygiene() {
        var status = reader.readSystemStatus().orElse(null);
        if (status == null) return List.of();
        var findings = jsonPath(status, "sections", "repo_hygiene", "top_findings");
        if (findings == null || !findings.isArray()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var item : findings) {
            rows.add(Map.of(
                    "kind", item.path("kind").asText(""),
                    "path", item.path("path").asText(""),
                    "severity", item.path("severity").asText("")
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> getCockpitState() {
        var status = reader.readSystemStatus().orElse(null);
        if (status == null) return List.of();
        var cockpit = jsonPath(status, "sections", "cockpit_lite");
        if (cockpit == null) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("field", "Status", "value", cockpit.path("status").asText("—")));
        rows.add(Map.of("field", "Port", "value", String.valueOf(cockpit.path("port").asInt(0))));
        rows.add(Map.of("field", "Notes Count", "value", String.valueOf(cockpit.path("notes_count").asInt(0))));
        rows.add(Map.of("field", "Frontend Errors", "value", String.valueOf(cockpit.path("frontend_console_error_count").asInt(0))));
        rows.add(Map.of("field", "Runtime Errors", "value", String.valueOf(cockpit.path("frontend_runtime_error_count").asInt(0))));
        rows.add(Map.of("field", "Telemetry", "value", cockpit.path("frontend_telemetry_status").asText("—")));
        rows.add(Map.of("field", "Last Event", "value", cockpit.path("last_frontend_event_message").asText("—")));
        return rows;
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
