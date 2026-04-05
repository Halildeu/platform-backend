package com.example.report.contexthealth;

import com.example.report.contexthealth.dto.ContextHealthGridMetaDto;
import com.example.report.contexthealth.dto.ContextHealthStatusDto;
import com.example.report.dto.ChartResultDto;
import com.example.report.dto.KpiResultDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/context-health")
@ConditionalOnProperty(name = "context-health.enabled", havingValue = "true")
public class ContextHealthController {

    private final ContextHealthKpiService kpiService;
    private final ContextHealthChartService chartService;
    private final ContextHealthGridService gridService;
    private final ContextHealthSessionService sessionService;
    private final ContextHealthFileReader fileReader;
    private final ContextHealthProperties props;
    private final CacheManager cacheManager;

    public ContextHealthController(
            ContextHealthKpiService kpiService,
            ContextHealthChartService chartService,
            ContextHealthGridService gridService,
            ContextHealthSessionService sessionService,
            ContextHealthFileReader fileReader,
            ContextHealthProperties props,
            CacheManager cacheManager
    ) {
        this.kpiService = kpiService;
        this.chartService = chartService;
        this.gridService = gridService;
        this.sessionService = sessionService;
        this.fileReader = fileReader;
        this.props = props;
        this.cacheManager = cacheManager;
    }

    @GetMapping("/status")
    public ContextHealthStatusDto getStatus() {
        String overall = fileReader.isConfigured() ? "OK" : "NOT_CONFIGURED";
        var statusNode = fileReader.readSystemStatus().orElse(null);
        if (statusNode != null) {
            overall = statusNode.path("overall_status").asText(overall);
        }
        return new ContextHealthStatusDto(
                props.enabled(),
                props.dataDir(),
                props.indexDir(),
                Instant.now().toString(),
                fileReader.countDataFiles(),
                overall
        );
    }

    @GetMapping("/kpis")
    public List<KpiResultDto> getKpis() {
        return kpiService.computeKpis();
    }

    @GetMapping("/charts")
    public List<ChartResultDto> getCharts() {
        return chartService.computeCharts();
    }

    @GetMapping("/grids")
    public List<ContextHealthGridMetaDto> getGrids() {
        return gridService.listGrids();
    }

    @GetMapping("/grids/{gridId}")
    public List<Map<String, Object>> getGridData(@PathVariable String gridId) {
        return gridService.getGridData(gridId);
    }

    @GetMapping("/session")
    public Map<String, Object> getSession() {
        return sessionService.getSessionData();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() {
        evictCaches("contextHealthFiles", "contextHealthKpis", "contextHealthCharts", "contextHealthGrids");
        return ResponseEntity.noContent().build();
    }

    private void evictCaches(String... names) {
        for (String name : names) {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }
    }
}
