package com.example.report.controller;

import com.example.report.repository.AlertRuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertRuleRepository alertRepository;

    public AlertController(AlertRuleRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @GetMapping("/{reportKey}")
    public ResponseEntity<List<Map<String, Object>>> listRules(@PathVariable String reportKey) {
        return ResponseEntity.ok(alertRepository.findByReportKey(reportKey));
    }

    @PostMapping("/{reportKey}")
    public ResponseEntity<Map<String, Object>> createRule(
            @PathVariable String reportKey,
            @RequestBody Map<String, Object> body) {
        body.put("reportKey", reportKey);
        Map<String, Object> saved = alertRepository.save(body);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/rule/{ruleId}")
    public ResponseEntity<Void> updateRule(
            @PathVariable String ruleId,
            @RequestBody Map<String, Object> body) {
        boolean updated = alertRepository.update(ruleId, body);
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/rule/{ruleId}")
    public ResponseEntity<Void> deleteRule(@PathVariable String ruleId) {
        boolean deleted = alertRepository.delete(ruleId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
