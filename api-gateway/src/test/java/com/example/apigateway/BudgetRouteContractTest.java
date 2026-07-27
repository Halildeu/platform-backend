package com.example.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class BudgetRouteContractTest {
    private static final String PREFIX = "spring.cloud.gateway.server.webflux.routes";

    @Test
    void budgetRoutePassesCanonicalApiPathWithoutStripping() throws Exception {
        Properties properties = loadMainProperties();
        String matchingIndex = null;
        for (int i = 0; i < 64; i++) {
            String predicate = properties.getProperty(PREFIX + "[" + i + "].predicates[0]");
            if ("Path=/api/v1/budgets/**".equals(predicate)) {
                matchingIndex = "[" + i + "]";
                break;
            }
        }

        assertThat(matchingIndex)
                .as("the complete /api/v1/budgets/** surface must have exactly one local gateway route")
                .isNotNull();
        String routeIndex = matchingIndex;
        assertThat(properties.getProperty(PREFIX + routeIndex + ".id"))
                .isEqualTo("budget-service-v1-route");
        assertThat(properties.getProperty(PREFIX + routeIndex + ".uri"))
                .contains("BUDGET_SERVICE_URL")
                .contains("8101");
        assertThat(properties.stringPropertyNames())
                .noneMatch(name -> name.startsWith(PREFIX + routeIndex + ".filters"));
    }

    @Test
    void kubernetesRouteAlsoPreservesTheCanonicalPath() throws Exception {
        Path path = Paths.get("src", "main", "resources", "application-k8s.yml");
        if (!Files.exists(path)) {
            path = Paths.get("api-gateway", "src", "main", "resources", "application-k8s.yml");
        }
        String yaml = Files.readString(path);
        assertThat(yaml).contains("- id: budget-service");
        assertThat(yaml).contains("uri: ${BUDGET_SERVICE_URL:http://budget-service:8101}");
        assertThat(yaml).contains("- Path=/api/v1/budgets/**");
        String budgetBlock = yaml.substring(yaml.indexOf("- id: budget-service"));
        budgetBlock = budgetBlock.substring(0, budgetBlock.indexOf("- id:", 1));
        assertThat(budgetBlock).doesNotContain("          - StripPrefix");
    }

    private Properties loadMainProperties() throws Exception {
        Path path = Paths.get("src", "main", "resources", "application.properties");
        if (!Files.exists(path)) {
            path = Paths.get("api-gateway", "src", "main", "resources", "application.properties");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }
}
