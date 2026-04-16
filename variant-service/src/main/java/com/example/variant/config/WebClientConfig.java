package com.example.variant.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

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

    @Bean
    @Primary
    @Qualifier("plainWebClientBuilder")
    public WebClient.Builder plainWebClientBuilder() {
        return baseWebClientBuilder();
    }

    private WebClient.Builder baseWebClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                .responseTimeout(Duration.ofSeconds(5));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
