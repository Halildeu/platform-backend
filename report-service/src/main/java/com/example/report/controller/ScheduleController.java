package com.example.report.controller;

import com.example.report.repository.ReportScheduleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ReportScheduleRepository scheduleRepository;

    public ScheduleController(ReportScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    @GetMapping("/{reportKey}")
    public ResponseEntity<Map<String, Object>> getSchedule(@PathVariable String reportKey) {
        return scheduleRepository.findByReportKey(reportKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{reportKey}")
    public ResponseEntity<Map<String, Object>> createSchedule(
            @PathVariable String reportKey,
            @RequestBody Map<String, Object> body) {
        body.put("reportKey", reportKey);
        Map<String, Object> saved = scheduleRepository.save(body);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{scheduleId}")
    public ResponseEntity<Void> updateSchedule(
            @PathVariable String scheduleId,
            @RequestBody Map<String, Object> body) {
        boolean updated = scheduleRepository.update(scheduleId, body);
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable String scheduleId) {
        boolean deleted = scheduleRepository.delete(scheduleId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
