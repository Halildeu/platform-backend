package com.example.schema.controller;

import com.example.schema.model.SchemaSnapshot;
import com.example.schema.service.ColumnLineageService;
import com.example.schema.service.SchemaExtractService;
import com.example.schema.service.SchemaSnapshotService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/schema/lineage")
public class LineageController {

    private final ColumnLineageService lineageService;
    private final SchemaSnapshotService snapshotService;
    private final SchemaExtractService extractService;

    @Value("${schema.default-schema:workcube_mikrolink}")
    private String defaultSchema;

    public LineageController(ColumnLineageService lineageService,
                              SchemaSnapshotService snapshotService,
                              SchemaExtractService extractService) {
        this.lineageService = lineageService;
        this.snapshotService = snapshotService;
        this.extractService = extractService;
    }

    @GetMapping("/{tableName}/{columnName}")
    public ResponseEntity<ColumnLineageService.LineageGraph> getColumnLineage(
            @PathVariable String tableName,
            @PathVariable String columnName,
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        Map<String, String> viewDefs = extractService.getViewDefinitions(target);
        return ResponseEntity.ok(lineageService.traceColumn(tableName, columnName, snapshot, viewDefs));
    }
}
