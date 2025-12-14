package com.example.user.authz;

import com.example.commonauth.AuthorizationContextCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AuthzCacheConfig {

    @Bean
    public AuthorizationContextCache authorizationContextCache(
            @Value("${authz.cache-ttl-seconds:300}") long ttlSeconds) {
        return new AuthorizationContextCache(Duration.ofSeconds(ttlSeconds));
    }
}
