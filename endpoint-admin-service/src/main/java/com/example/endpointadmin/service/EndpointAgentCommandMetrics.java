package com.example.endpointadmin.service;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Faz 22.5 M6 (#1493, sub-task of #1378) capacity-baseline instrumentation.
 *
 * <p>Owns the {@code endpoint_admin_agent_command_results_total} counter
 * (Prometheus exposition name) tagged by {@code command_type} and
 * {@code status}. This is the one M6 wave-abort / capacity-baseline metric that
 * <em>cannot</em> be derived from the Spring Boot auto-exposed
 * {@code http_server_requests_seconds_count}: the agent {@code COLLECT_INVENTORY}
 * workload is a {@link CommandType} carried over the generic
 * {@code POST /api/v1/agent/commands/{id}/result} endpoint, so the command type
 * is not an HTTP {@code uri} label. {@code RB-faz22.5-m6-capacity-baseline.md}
 * reads {@code rate(endpoint_admin_agent_command_results_total
 * {command_type="COLLECT_INVENTORY"}[5m])} for the COLLECT_INVENTORY rate.
 *
 * <p>Design (Codex {@code 019ebffb} plan-time review absorb):
 * <ul>
 *   <li><b>after-commit</b> — {@link #recordResultAfterCommit} defers the
 *       increment to {@link TransactionSynchronization#afterCommit()} so a
 *       command result that is persisted but whose surrounding transaction then
 *       fails to commit (deadlock, deferred constraint, connection loss) is NOT
 *       counted. A Micrometer increment is not transactional and would
 *       otherwise over-count. With no active transaction synchronization
 *       (unit tests, no transaction) it increments immediately.</li>
 *   <li><b>pre-registered</b> — every {@link CommandType} &times;
 *       {@link CommandResultStatus} counter is created at zero on construction
 *       so the series exist in {@code /actuator/prometheus} immediately after
 *       deploy. The M6 abort formula must not misread a lazily-absent series as
 *       0. Bounded cardinality {@code 13 &times; 4 = 52}.</li>
 *   <li><b>bounded tags</b> — only the two enum-valued tags; never tenant,
 *       device, command id, claim id, or raw error text.</li>
 * </ul>
 */
@Component
public class EndpointAgentCommandMetrics {

    /** Micrometer meter name; Prometheus exposition appends {@code _total}. */
    static final String METER_NAME = "endpoint.admin.agent.command.results";

    private final MeterRegistry meterRegistry;

    @Autowired
    public EndpointAgentCommandMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // Mirror OpenFgaAuthzConfig: the registry is optionally present. In the
        // running service the @Primary CompositeMeterRegistry (backed by the
        // Prometheus registry) is injected; in a @DataJpaTest slice without
        // metrics auto-configuration it falls back to a private
        // SimpleMeterRegistry so the bean is always constructible and the
        // collaborator the command service depends on is never null.
        this(meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    // Package-private — direct construction with a known registry for the unit
    // test (a PrometheusMeterRegistry to assert the exact exposition name the
    // M6 runbook depends on) and for the service-package branch tests.
    EndpointAgentCommandMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (CommandType type : CommandType.values()) {
            for (CommandResultStatus status : CommandResultStatus.values()) {
                counter(type, status);
            }
        }
    }

    /**
     * Record one durably-persisted terminal agent command result. Reached only
     * on the submit-result success path — the idempotent duplicate re-submit
     * returns early and validation rejections throw before this point. The
     * increment is deferred to after the surrounding transaction commits; with
     * no active transaction synchronization (unit tests) it increments
     * immediately.
     */
    public void recordResultAfterCommit(CommandType commandType, CommandResultStatus status) {
        if (commandType == null || status == null) {
            return;
        }
        Counter counter = counter(commandType, status);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
        } else {
            counter.increment();
        }
    }

    private Counter counter(CommandType commandType, CommandResultStatus status) {
        return Counter.builder(METER_NAME)
                .description("Terminal agent command result submissions accepted (durably persisted), "
                        + "by command type and agent-reported status.")
                .tag("command_type", commandType.name())
                .tag("status", status.name())
                .register(meterRegistry);
    }
}
