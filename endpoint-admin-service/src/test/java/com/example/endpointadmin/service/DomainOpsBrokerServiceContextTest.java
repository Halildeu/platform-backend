package com.example.endpointadmin.service;

import com.example.endpointadmin.domainops.DomainOpsConnector;
import com.example.endpointadmin.domainops.DomainOpsExecutionConnectorConfig;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointDomainOpsRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DomainOpsBrokerServiceContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context ->
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(EndpointDeviceRepository.class, () -> mock(EndpointDeviceRepository.class))
            .withBean(EndpointDomainOpsRequestRepository.class, () -> mock(EndpointDomainOpsRequestRepository.class))
            .withBean(EndpointAuditService.class, () -> mock(EndpointAuditService.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(DomainOpsBrokerService.class);

    @Test
    void defaultConnectorPathWiresInSpringContext() {
        runner.withPropertyValues(
                        "endpoint-admin.domain-ops.enabled=false",
                        "endpoint-admin.domain-ops.max-permit-ttl=PT15M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DomainOpsBrokerService.class);
                });
    }

    @Test
    void executionConnectorIsDisabledByDefault() {
        runner.withUserConfiguration(DomainOpsExecutionConnectorConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DomainOpsConnector.class);
                });
    }

    @Test
    void executionConnectorWiresOnlyWhenExplicitlyEnabled() {
        runner.withUserConfiguration(DomainOpsExecutionConnectorConfig.class)
                .withPropertyValues(
                        "endpoint-admin.domain-ops.execution-connector.enabled=true",
                        "endpoint-admin.domain-ops.execution-connector.mode=dispatch-plan-only",
                        "endpoint-admin.domain-ops.execution-connector.allowed-credential-ref-prefixes[0]=vault:domain-ops/")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DomainOpsConnector.class);
                    assertThat(context.getBean(DomainOpsConnector.class).name())
                            .isEqualTo("domain-ops-execution-connector");
                });
    }
}
