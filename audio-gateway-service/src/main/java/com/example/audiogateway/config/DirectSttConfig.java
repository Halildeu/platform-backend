package com.example.audiogateway.config;

import com.example.audiogateway.service.AudioChunkDispatcher;
import com.example.audiogateway.service.AudioGatewayAuditSink;
import com.example.audiogateway.service.DirectSttForwardingDispatcher;
import com.example.audiogateway.service.DirectSttTranscriptResultSink;
import com.example.audiogateway.service.NoOpAudioChunkDispatcher;
import com.example.audiogateway.service.RedisStreamsAudioChunkDispatcher;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.time.Duration;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Direct-STT wiring — Faz 24 issue #182 (architecture "A").
 *
 * <p>Active ONLY when {@code audio.gateway.direct-stt.enabled=true} (DEFAULT-OFF). When the
 * flag is off this whole configuration is skipped and the
 * {@link com.example.audiogateway.service.AudioChunkDispatcher} injected into the controller
 * is whichever base bean {@code dispatcher.mode} selects — i.e. behaviour is unchanged.
 *
 * <p><b>Why a factory (Codex {@code 019eeb5f} REVISE point 6 — avoid the self-injection
 * footgun).</b> The decorator is {@link Primary @Primary}, so it cannot itself depend on a
 * {@code @Primary AudioChunkDispatcher} (it would resolve to itself / be ambiguous). Instead
 * this factory resolves the base delegate explicitly by {@code dispatcher.mode}
 * ({@code redis → RedisStreamsAudioChunkDispatcher}, else {@code NoOpAudioChunkDispatcher})
 * via type-specific {@link ObjectProvider}s, and constructs the decorator around it. The
 * decorator class stays free of any Spring lookup logic.
 *
 * <p><b>Two-primary collision fix (Spring footgun).</b> When BOTH {@code mode=redis} AND
 * {@code direct-stt.enabled=true}, {@link RedisStreamsAudioChunkDispatcher} (a
 * {@code @Component @Primary}) and this decorator ({@code @Bean @Primary}) would both be
 * {@code @Primary AudioChunkDispatcher} candidates → the single controller injection point
 * throws {@code NoUniqueBeanDefinitionException} ("more than one 'primary' bean found").
 * {@code @Order}/{@code @Priority} do NOT break {@code @Primary} ties. So
 * {@link #directSttPrimaryDemoter} (a {@link BeanFactoryPostProcessor}) demotes the base
 * dispatcher's {@code primary} flag at bean-definition time (after {@code @ConditionalOnProperty}
 * evaluation, before instantiation), leaving the decorator the sole primary. This touches no
 * existing class. When the flag is off, this whole config is absent and the Redis bean keeps
 * its primacy.
 */
@Configuration
@ConditionalOnProperty(name = "audio.gateway.direct-stt.enabled", havingValue = "true")
public class DirectSttConfig {

    /**
     * Bean names that, when present, would be a SECOND {@code @Primary AudioChunkDispatcher}
     * alongside this decorator. Demoted to non-primary so the decorator is the unambiguous
     * winner. {@code NoOpAudioChunkDispatcher} is not primary, so only the Redis bean matters;
     * the conventional component bean name is the de-capitalized simple class name.
     */
    private static final String REDIS_DISPATCHER_BEAN = "redisStreamsAudioChunkDispatcher";

    /**
     * Demote the base Redis dispatcher's {@code primary} flag so this decorator is the sole
     * {@code @Primary AudioChunkDispatcher}. {@code static} so it is instantiated early without
     * forcing premature instantiation of regular beans (standard BFPP requirement). No-op when
     * the Redis bean is absent (e.g. {@code mode=noop}).
     */
    @Bean
    static BeanFactoryPostProcessor directSttPrimaryDemoter() {
        return (final ConfigurableListableBeanFactory bf) -> {
            if (bf.containsBeanDefinition(REDIS_DISPATCHER_BEAN)) {
                final BeanDefinition bd = bf.getBeanDefinition(REDIS_DISPATCHER_BEAN);
                if (bd.isPrimary()) {
                    bd.setPrimary(false);
                }
            }
        };
    }

    /**
     * Dedicated WebClient for the live-stt {@code /transcribe} forward. Bounded connect +
     * (per-request) response timeouts and a small in-memory response decode cap — the
     * {@code /transcribe} JSON is small. When {@code direct-stt.tls.enabled=true}, the
     * client presents the mounted audio-gateway certificate and verifies the Caddy/live-stt
     * server certificate with HTTPS hostname verification. No base URL: {@code forward()}
     * builds the absolute URI from {@code transcribe-url} + query params per request.
     */
    @Bean("directSttWebClient")
    public WebClient directSttWebClient(final AudioGatewayProperties props) throws SSLException {
        final AudioGatewayProperties.DirectStt cfg = props.getDirectStt();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) cfg.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(cfg.getResponseTimeoutMs()));
        if (cfg.getTls().isEnabled()) {
            httpClient = applyMutualTls(httpClient, cfg.getTls());
        }
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(cfg.getMaxResponseBytes()))
                .build();
    }

    private HttpClient applyMutualTls(
            final HttpClient httpClient,
            final AudioGatewayProperties.DirectStt.Tls tls) throws SSLException {
        final SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(new File(tls.getCaCertificatePath()))
                .keyManager(
                        new File(tls.getClientCertificatePath()),
                        new File(tls.getClientPrivateKeyPath()))
                .build();
        return httpClient.secure(spec -> spec
                .sslContext(sslContext)
                .handlerConfigurator(handler -> {
                    final SSLParameters parameters = handler.engine().getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    handler.engine().setSSLParameters(parameters);
                }));
    }

    @Bean
    @ConditionalOnMissingBean(DirectSttTranscriptResultSink.class)
    public DirectSttTranscriptResultSink noOpDirectSttTranscriptResultSink() {
        return DirectSttTranscriptResultSink.noop();
    }

    /**
     * The {@code @Primary} decorating dispatcher. Wraps the mode-selected base dispatcher and
     * adds the bounded, fire-and-forget direct audio forward. Becomes the bean injected into
     * {@code AudioSessionController}; the base dispatcher's metadata path is preserved.
     */
    @Bean
    @Primary
    public AudioChunkDispatcher directSttForwardingDispatcher(
            final AudioGatewayProperties props,
            final MeterRegistry meters,
            final AudioGatewayAuditSink auditSink,
            final DirectSttTranscriptResultSink transcriptResultSink,
            @org.springframework.beans.factory.annotation.Qualifier("directSttWebClient")
            final WebClient directSttWebClient,
            final ObjectProvider<RedisStreamsAudioChunkDispatcher> redisProvider,
            final ObjectProvider<NoOpAudioChunkDispatcher> noOpProvider) {

        final AudioChunkDispatcher delegate = resolveDelegate(
                props.getDispatcher().getMode(), redisProvider, noOpProvider);

        return new DirectSttForwardingDispatcher(
                delegate, auditSink, transcriptResultSink, directSttWebClient, props, meters);
    }

    private AudioChunkDispatcher resolveDelegate(
            final String mode,
            final ObjectProvider<RedisStreamsAudioChunkDispatcher> redisProvider,
            final ObjectProvider<NoOpAudioChunkDispatcher> noOpProvider) {

        if ("redis".equalsIgnoreCase(mode)) {
            final RedisStreamsAudioChunkDispatcher redis = redisProvider.getIfAvailable();
            if (redis == null) {
                // mode=redis but the Redis bean is absent (its @ConditionalOnProperty did not
                // match) — fail-closed loudly rather than silently degrading the metadata path.
                throw new IllegalStateException(
                        "audio.gateway.dispatcher.mode=redis but RedisStreamsAudioChunkDispatcher "
                                + "bean is not present; direct-stt cannot wrap a missing delegate");
            }
            return redis;
        }
        final NoOpAudioChunkDispatcher noOp = noOpProvider.getIfAvailable();
        if (noOp == null) {
            throw new IllegalStateException(
                    "NoOpAudioChunkDispatcher base bean missing; cannot wire direct-stt delegate");
        }
        return noOp;
    }
}
