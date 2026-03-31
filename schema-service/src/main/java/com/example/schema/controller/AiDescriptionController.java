package com.example.schema.controller;

import com.example.schema.model.SchemaSnapshot;
import com.example.schema.service.AiDescriptionService;
import com.example.schema.service.SchemaSnapshotService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schema/ai-descriptions")
public class AiDescriptionController {

    private final AiDescriptionService descriptionService;
    private final SchemaSnapshotService snapshotService;

    @Value("${schema.default-schema:workcube_mikrolink}")
    private String defaultSchema;

    public AiDescriptionController(AiDescriptionService descriptionService,
                                    SchemaSnapshotService snapshotService) {
        this.descriptionService = descriptionService;
        this.snapshotService = snapshotService;
    }

    @GetMapping("/table/{tableName}")
    public ResponseEntity<List<AiDescriptionService.Description>> describeTable(
            @PathVariable String tableName,
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        return ResponseEntity.ok(descriptionService.generateForTable(tableName, snapshot));
    }

    @GetMapping("/batch")
    public ResponseEntity<Map<String, Object>> describeBatch(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        var descriptions = descriptionService.generateBatch(snapshot, limit);
        return ResponseEntity.ok(Map.of(
            "count", descriptions.size(),
            "descriptions", descriptions
        ));
    }
}
