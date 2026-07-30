package com.example.user.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.springframework.beans.factory.annotation.Value;

/**
 * D7 (platform-k8s-gitops 2026-04-15): @LoadBalanced KALDIRILDI.
 * K8s native DNS kullanılır — downstream servisler yapılandırma üzerinden
 * explicit URL'lerle çağrılır (svc.cluster.local).
 *
 * Eski `loadBalancedWebClientBuilder` bean'i silindi; tüm kullanıcılar
 * `plainWebClientBuilder` qualifier'ına taşındı.
 */
@Configuration
public class WebClientConfig {

    @Value("${user.webclient.response-timeout-seconds:30}")
    private long responseTimeoutSeconds;

    @Value("${user.webclient.connect-timeout-millis:3000}")
    private int connectTimeoutMillis;

    @Bean
    @Primary
    @Qualifier("plainWebClientBuilder")
    public WebClient.Builder plainWebClientBuilder() {
        return baseBuilder();
    }

    @Bean
    @Qualifier("directWebClientBuilder")
    public WebClient.Builder directWebClientBuilder() {
        return baseBuilder();
    }

    /**
     * Response timeout, configurable because 5s is measurably too tight for one call on this
     * client: {@code /api/v1/users} blocks a request thread on permission-service
     * {@code /authz/me}, and permission-service resolves the principal by calling back into
     * user-service ({@code /api/users/by-email/...}). That nested hop is documented as a
     * deliberate shortcut in {@code AuthenticatedUserLookupIdentityDirectory} — "adding that
     * plumbing here would blow the wire step's scope" — so the round trip is user-service →
     * permission-service → user-service before a single byte comes back.
     *
     * <p>Measured on the live cell 2026-07-28: the admin's {@code /admin/users} request failed
     * with {@code ReadTimeoutException} at exactly 5s, repeatedly, while every component was idle
     * (OpenFGA reads 4ms, permission-service 2m CPU) and a fresh pod behaved identically. Raising
     * the ceiling does not make the nested hop correct; it stops a slow-but-working chain from
     * being reported as a failure. Removing the callback is the real fix and is tracked separately.
     */
    private WebClient.Builder baseBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
