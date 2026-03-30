package com.example.schema.controller;

import com.example.schema.service.AnnotationService;
import com.example.schema.service.AnnotationService.Annotation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schema/annotations")
public class AnnotationController {

    private final AnnotationService annotationService;

    public AnnotationController(AnnotationService annotationService) {
        this.annotationService = annotationService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Annotation>> getAll() {
        return ResponseEntity.ok(annotationService.getAll());
    }

    @GetMapping("/table/{tableName}")
    public ResponseEntity<List<Annotation>> getForTable(@PathVariable String tableName) {
        return ResponseEntity.ok(annotationService.getForTable(tableName));
    }

    @PutMapping("/{key}")
    public ResponseEntity<Annotation> save(
            @PathVariable String key,
            @RequestBody Map<String, Object> body) {
        String table = (String) body.getOrDefault("table", "");
        String column = (String) body.get("column");
        String text = (String) body.getOrDefault("text", "");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.getOrDefault("tags", List.of());
        String author = (String) body.getOrDefault("author", "anonymous");
        return ResponseEntity.ok(annotationService.save(key, table, column, text, tags, author));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        annotationService.delete(key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importAnnotations(@RequestBody Map<String, Annotation> annotations) {
        int imported = annotationService.importAnnotations(annotations);
        return ResponseEntity.ok(Map.of("imported", imported, "total", annotationService.getAll().size()));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(annotationService.stats());
    }
}
