package com.example.report.contexthealth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ContextHealthSessionService {

    private final ContextHealthFileReader reader;

    public ContextHealthSessionService(ContextHealthFileReader reader) {
        this.reader = reader;
    }

    @Cacheable(value = "contextHealthGrids", key = "'session'")
    public Map<String, Object> getSessionData() {
        var status = reader.readSystemStatus().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();

        if (status == null) {
            result.put("available", false);
            return result;
        }

        result.put("available", true);
        result.put("generatedAt", status.path("generated_at").asText(""));
        result.put("overallStatus", status.path("overall_status").asText("UNKNOWN"));

        // Session
        var session = jsonPath(status, "sections", "session");
        if (session != null) {
            result.put("sessionId", session.path("session_id").asText(""));
            result.put("sessionStatus", session.path("status").asText(""));
            result.put("contextHash", truncateHash(session.path("session_context_hash").asText("")));
            result.put("ttlSeconds", session.path("ttl_seconds").asInt(0));
            result.put("expiresAt", session.path("expires_at").asText(""));
        }

        // Readiness
        var readiness = jsonPath(status, "sections", "readiness");
        if (readiness != null) {
            result.put("readinessStatus", readiness.path("status").asText(""));
            result.put("readinessFails", readiness.path("fails").asInt(0));
            result.put("readinessWarns", readiness.path("warns").asInt(0));
        }

        // Core Lock
        var coreLock = jsonPath(status, "sections", "core_lock");
        if (coreLock != null) {
            result.put("coreLockEnabled", coreLock.path("enabled").asBoolean(false));
            result.put("coreLockStatus", coreLock.path("status").asText(""));
            result.put("coreWriteMode", coreLock.path("core_write_mode").asText(""));
        }

        // Core Integrity
        var integrity = jsonPath(status, "sections", "core_integrity");
        if (integrity != null) {
            result.put("coreIntegrityStatus", integrity.path("status").asText(""));
            result.put("dirtyFilesCount", integrity.path("dirty_files_count").asInt(0));
            result.put("gitClean", integrity.path("git_clean").asBoolean(false));
        }

        // Network
        var network = jsonPath(status, "sections", "network_live");
        if (network != null) {
            result.put("networkEnabled", network.path("enabled").asBoolean(false));
        }

        // AIRunner
        var airunner = jsonPath(status, "sections", "airunner");
        if (airunner != null) {
            result.put("airunnerStatus", airunner.path("status").asText("IDLE"));
            result.put("airunnerLockStatus", airunner.path("lock").path("status").asText("IDLE"));
            var autoMode = airunner.path("auto_mode");
            result.put("airunnerAutoMode", autoMode.path("enabled").asBoolean(false));
            result.put("airunnerMode", autoMode.path("mode").asText(""));
        }

        // Doer Loop
        var doer = jsonPath(status, "sections", "doer_loop");
        if (doer != null) {
            result.put("doerLoopState", doer.path("lock_state").asText("MISSING"));
        }

        // Role Handoff
        var handoff = jsonPath(status, "sections", "role_handoff");
        if (handoff != null) {
            result.put("handoffCount", handoff.path("handoff_count").asInt(0));
            result.put("closeoutCount", handoff.path("closeout_count").asInt(0));
            result.put("handoffStatus", handoff.path("status").asText(""));
        }

        // Execution Target
        var execTarget = jsonPath(status, "sections", "execution_target_governance");
        if (execTarget != null) {
            result.put("targetCount", execTarget.path("target_count").asInt(0));
            result.put("repoCount", execTarget.path("repo_count").asInt(0));
            result.put("registryFirst", execTarget.path("registry_first").asBoolean(false));
        }

        // Provider Capability
        var providers = jsonPath(status, "sections", "provider_capability");
        if (providers != null) {
            result.put("providerCount", providers.path("provider_count").asInt(0));
            result.put("capabilityCount", providers.path("capability_count").asInt(0));
        }

        return result;
    }

    private String truncateHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) + "..." : hash;
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
