package com.example.audiogateway.config;

import com.example.audiogateway.service.MeetingAccessValidator;
import com.example.audiogateway.service.MeetingServiceAccessValidator;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * meeting-service access-validation client for recorder start-session.
 */
@Configuration
public class MeetingAccessConfig {

    @Bean("meetingServiceWebClient")
    public WebClient meetingServiceWebClient(final AudioGatewayProperties props) {
        final AudioGatewayProperties.MeetingAccess cfg = props.getMeetingAccess();
        final HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) cfg.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(cfg.getResponseTimeoutMs()));
        return WebClient.builder()
                .baseUrl(cfg.getBaseUrl().trim())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(cfg.getMaxResponseBytes()))
                .build();
    }

    @Bean
    public MeetingAccessValidator meetingAccessValidator(
            final AudioGatewayProperties props,
            @Qualifier("meetingServiceWebClient") final WebClient meetingServiceWebClient) {
        return new MeetingServiceAccessValidator(props, meetingServiceWebClient);
    }
}
