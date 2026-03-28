package com.example.user.config;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LocalJwtDecoderConfigurationTest {

    @Test
    void resolveAudiences_should_keep_all_configured_values() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("security.service-auth.expected-audience", "user-service,frontend,account,serban-web");

        LocalJwtDecoderConfiguration configuration = new LocalJwtDecoderConfiguration(environment);

        List<String> audiences = configuration.resolveAudiences();

        assertThat(audiences).containsExactly(
                "user-service",
                "frontend",
                "account",
                "serban-web"
        );
    }
}
