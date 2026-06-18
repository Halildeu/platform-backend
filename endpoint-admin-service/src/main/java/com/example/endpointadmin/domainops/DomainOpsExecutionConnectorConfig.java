package com.example.endpointadmin.domainops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(DomainOpsExecutionConnectorProperties.class)
public class DomainOpsExecutionConnectorConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "endpoint-admin.domain-ops.execution-connector",
            name = "enabled",
            havingValue = "true")
    public DomainOpsConnector domainOpsExecutionConnector(DomainOpsExecutionConnectorProperties properties,
                                                          Clock clock) {
        return new DomainOpsExecutionConnector(properties, clock);
    }
}
