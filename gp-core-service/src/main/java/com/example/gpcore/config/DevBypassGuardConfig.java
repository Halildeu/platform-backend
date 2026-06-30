package com.example.gpcore.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Wires {@link DevBypassGuard} as a startup (fail-fast) check: if
 * {@code gp.authz.dev-bypass=true} is set outside a dev profile, context
 * initialization throws and the service refuses to start.
 */
@Configuration
public class DevBypassGuardConfig {

    @Bean
    InitializingBean devBypassGuard(Environment environment,
                                    @Value("${gp.authz.dev-bypass:false}") boolean devBypass) {
        return () -> DevBypassGuard.validate(devBypass, List.of(environment.getActiveProfiles()));
    }
}
