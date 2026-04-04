package com.example.report.contexthealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class ContextHealthFileReader {

    private static final Logger log = LoggerFactory.getLogger(ContextHealthFileReader.class);
    private static final Duration STALE_THRESHOLD = Duration.ofHours(1);

    private final ContextHealthProperties props;
    private final ObjectMapper mapper;

    public ContextHealthFileReader(ContextHealthProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Cacheable(value = "contextHealthFiles", key = "'system_status'")
    public Optional<JsonNode> readSystemStatus() {
        return readFromDataDir("system_status.v1.json");
    }

    @Cacheable(value = "contextHealthFiles", key = "'portfolio_status'")
    public Optional<JsonNode> readPortfolioStatus() {
        return readFromDataDir("portfolio_status.v1.json");
    }

    @Cacheable(value = "contextHealthFiles", key = "'bootstrap_evidence'")
    public Optional<JsonNode> readBootstrapEvidence() {
        return readFromDataDir("bootstrap_evidence.v1.json");
    }

    @Cacheable(value = "contextHealthFiles", key = "'doc_graph'")
    public Optional<JsonNode> readDocGraph() {
        return readFromDataDir("doc_graph_report.v1.json");
    }

    @Cacheable(value = "contextHealthFiles", key = "'drift_scoreboard'")
    public Optional<JsonNode> readDriftScoreboard() {
        return readFromDataDir("drift_scoreboard.v1.json");
    }

    @Cacheable(value = "contextHealthFiles", key = "'error_observability'")
    public Optional<JsonNode> readErrorObservability() {
        return readFromDataDir("error_observability.v1.json");
    }

    @Cacheable(value = "contextHealthFiles", key = "'extension_registry'")
    public Optional<JsonNode> readExtensionRegistry() {
        return readFromIndexDir("extension_registry.v1.json");
    }

    @Cacheable(value = "contextHealthFiles", key = "'quality_gate'")
    public Optional<JsonNode> readQualityGate() {
        return readFromIndexDir("quality_gate_report.v1.json");
    }

    public boolean isConfigured() {
        return props.enabled()
                && props.dataDir() != null && !props.dataDir().isBlank()
                && Files.isDirectory(Path.of(props.dataDir()));
    }

    public int countDataFiles() {
        if (!isConfigured()) return 0;
        try (var stream = Files.list(Path.of(props.dataDir()))) {
            return (int) stream.filter(p -> p.toString().endsWith(".json")).count();
        } catch (Exception e) {
            log.warn("Failed to count data files: {}", e.getMessage());
            return 0;
        }
    }

    public boolean isStale(JsonNode node) {
        if (node == null || !node.has("generated_at")) return true;
        try {
            var generatedAt = Instant.parse(node.get("generated_at").asText());
            return Duration.between(generatedAt, Instant.now()).compareTo(STALE_THRESHOLD) > 0;
        } catch (Exception e) {
            return true;
        }
    }

    private Optional<JsonNode> readFromDataDir(String filename) {
        return readFile(props.dataDir(), filename);
    }

    private Optional<JsonNode> readFromIndexDir(String filename) {
        String dir = props.indexDir() != null && !props.indexDir().isBlank()
                ? props.indexDir()
                : props.dataDir();
        return readFile(dir, filename);
    }

    private Optional<JsonNode> readFile(String dir, String filename) {
        if (dir == null || dir.isBlank()) {
            log.debug("Directory not configured for file: {}", filename);
            return Optional.empty();
        }
        Path path = Path.of(dir, filename);
        if (!Files.isRegularFile(path)) {
            log.debug("File not found: {}", path);
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(path.toFile());
            if (isStale(node)) {
                log.info("Stale data detected in: {}", filename);
            }
            return Optional.of(node);
        } catch (Exception e) {
            log.warn("Failed to read {}: {}", filename, e.getMessage());
            return Optional.empty();
        }
    }
}
