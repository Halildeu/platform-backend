package com.example.report.contexthealth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ContextHealthProperties.class)
public class ContextHealthConfig {
}
