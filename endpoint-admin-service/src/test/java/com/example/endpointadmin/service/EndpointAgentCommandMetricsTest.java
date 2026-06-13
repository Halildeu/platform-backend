package com.example.endpointadmin.service;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 22.5 M6 (#1493) — guards the capacity-baseline counter the M6 runbook
 * PromQL binds to. Uses a real {@link PrometheusMeterRegistry} so the exact
 * exposition name {@code endpoint_admin_agent_command_results_total} (the
 * {@code _total} suffix + dot-to-underscore conversion) is asserted, not just
 * the Micrometer meter id — the runbook abort formula depends on that literal
 * series name.
 */
class EndpointAgentCommandMetricsTest {

    @Test
    void preRegistersEveryCommandTypeAndStatusAtZero() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new EndpointAgentCommandMetrics(registry);

        long series = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(EndpointAgentCommandMetrics.METER_NAME))
                .count();
        assertThat(series)
                .isEqualTo((long) CommandType.values().length * CommandResultStatus.values().length);

        // The COLLECT_INVENTORY series the M6 runbook reads exists at 0 before
        // any result lands — never lazily-absent.
        assertThat(counterValue(registry, CommandType.COLLECT_INVENTORY, CommandResultStatus.SUCCEEDED))
                .isZero();
        String scrape = registry.scrape();
        assertThat(scrape).contains("endpoint_admin_agent_command_results_total");
        assertThat(scrape).contains("command_type=\"COLLECT_INVENTORY\"");
        assertThat(scrape).contains("status=\"SUCCEEDED\"");
    }

    @Test
    void incrementsImmediatelyWhenNoTransactionActive() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        EndpointAgentCommandMetrics metrics = new EndpointAgentCommandMetrics(registry);

        metrics.recordResultAfterCommit(CommandType.COLLECT_INVENTORY, CommandResultStatus.SUCCEEDED);

        assertThat(counterValue(registry, CommandType.COLLECT_INVENTORY, CommandResultStatus.SUCCEEDED))
                .isEqualTo(1.0d);
        // Other (command_type, status) cells stay at zero.
        assertThat(counterValue(registry, CommandType.INSTALL_SOFTWARE, CommandResultStatus.SUCCEEDED))
                .isZero();
    }

    @Test
    void defersIncrementUntilTransactionCommit() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        EndpointAgentCommandMetrics metrics = new EndpointAgentCommandMetrics(registry);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.recordResultAfterCommit(CommandType.COLLECT_INVENTORY, CommandResultStatus.SUCCEEDED);
            // Registered but deferred — not yet counted while the tx is open.
            assertThat(counterValue(registry, CommandType.COLLECT_INVENTORY, CommandResultStatus.SUCCEEDED))
                    .isZero();

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.forEach(TransactionSynchronization::afterCommit);

            assertThat(counterValue(registry, CommandType.COLLECT_INVENTORY, CommandResultStatus.SUCCEEDED))
                    .isEqualTo(1.0d);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void doesNotIncrementWhenTransactionRollsBack() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        EndpointAgentCommandMetrics metrics = new EndpointAgentCommandMetrics(registry);

        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.recordResultAfterCommit(CommandType.COLLECT_INVENTORY, CommandResultStatus.FAILED);
            // Rollback: afterCompletion(STATUS_ROLLED_BACK) fires, afterCommit does NOT.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(counterValue(registry, CommandType.COLLECT_INVENTORY, CommandResultStatus.FAILED))
                .isZero();
    }

    @Test
    void ignoresNullArguments() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        EndpointAgentCommandMetrics metrics = new EndpointAgentCommandMetrics(registry);

        metrics.recordResultAfterCommit(null, CommandResultStatus.SUCCEEDED);
        metrics.recordResultAfterCommit(CommandType.COLLECT_INVENTORY, null);

        assertThat(counterValue(registry, CommandType.COLLECT_INVENTORY, CommandResultStatus.SUCCEEDED))
                .isZero();
    }

    private static double counterValue(PrometheusMeterRegistry registry,
                                       CommandType type,
                                       CommandResultStatus status) {
        return registry.get(EndpointAgentCommandMetrics.METER_NAME)
                .tag("command_type", type.name())
                .tag("status", status.name())
                .counter()
                .count();
    }
}
