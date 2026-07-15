package com.example.endpointadmin.remoteaccess.policy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

@Configuration
@ConditionalOnProperty(prefix = "remote-view-policy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RemoteViewPolicyProperties.class)
public class RemoteViewPolicyCoreConfig {

    @Bean
    RemoteViewJsonCanonicalizer remoteViewJsonCanonicalizer() {
        return new RemoteViewJsonCanonicalizer();
    }

    @Bean
    RemoteViewPolicyArtifacts remoteViewPolicyArtifacts(RemoteViewPolicyProperties properties,
                                                        RemoteViewJsonCanonicalizer canonicalizer) {
        return RemoteViewPolicyArtifacts.load(properties, canonicalizer);
    }

    @Bean
    RemoteViewPolicyValidator remoteViewPolicyValidator(RemoteViewJsonCanonicalizer canonicalizer,
                                                        RemoteViewPolicyArtifacts artifacts) {
        return new RemoteViewPolicyValidator(canonicalizer, artifacts);
    }

    @Bean
    RemoteViewPolicyKeyRegistry remoteViewPolicyKeyRegistry(RemoteViewPolicyProperties properties) {
        return new RemoteViewPolicyKeyRegistry(properties);
    }

    @Bean
    RemoteViewSessionPolicyResolver remoteViewSessionPolicyResolver(
            com.example.endpointadmin.repository.RemoteViewPolicyPublicationRepository publications,
            com.example.endpointadmin.repository.RemoteViewPolicyRevocationRepository revocations,
            RemoteViewPolicyValidator validator, RemoteViewPolicyArtifacts artifacts,
            RemoteViewJsonCanonicalizer canonicalizer, RemoteViewPolicyKeyRegistry keys,
            RemoteViewPolicyProperties properties, java.time.Clock clock) {
        return new RemoteViewSessionPolicyResolver(publications, revocations, validator, artifacts, canonicalizer, keys,
                properties, clock, new SecureRandom());
    }
}
