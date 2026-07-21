package com.example.audiogateway.config;

import com.example.audiogateway.service.AudioChunkDispatcher;
import com.example.audiogateway.service.AudioGatewayAuditSink;
import com.example.audiogateway.service.DirectSttForwardingDispatcher;
import com.example.audiogateway.service.DirectSttTranscriptResultSink;
import com.example.audiogateway.service.LiveAnalyzeTrigger;
import com.example.audiogateway.service.LiveAnalyzeTriggerSink;
import com.example.audiogateway.service.LiveTranscriptBroadcastSink;
import com.example.audiogateway.service.LiveTranscriptStreamHub;
import com.example.audiogateway.service.NoOpAudioChunkDispatcher;
import com.example.audiogateway.service.RedisStreamsAudioChunkDispatcher;
import com.example.audiogateway.service.SentenceAssemblingSink;
import com.example.audiogateway.service.SentenceAssemblyPolicy;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.time.Duration;
import java.util.List;
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

    static HttpClient applyMutualTls(
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
     * Faz 24 İ4 — WebClient dedicated to the meeting-ai {@code /analyze/live}
     * hop. Bounded connect + response timeout so a slow meeting-ai never
     * back-pressures the STT forwarding path. NOT the audio WebClient: this
     * is a JSON POST to a different host and belongs on its own bean.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "audio.gateway.direct-stt.live-analyze",
            name = "enabled",
            havingValue = "true")
    public WebClient meetingAiLiveAnalyzeWebClient(final AudioGatewayProperties props) {
        final AudioGatewayProperties.DirectStt.LiveAnalyze cfg =
                props.getDirectStt().getLiveAnalyze();
        cfg.validate();
        final HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, cfg.getTimeoutMs())
                .responseTimeout(Duration.ofMillis(cfg.getTimeoutMs()));
        return WebClient.builder()
                .baseUrl(cfg.getBaseUrl().trim().replaceAll("/+$", ""))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Faz 24 İ4 — the aggregator that turns per-meeting transcript results into
     * cadenced {@code /analyze/live} POSTs. Bean present only when the config
     * enables live-analyze; otherwise the sink chain is unchanged.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "audio.gateway.direct-stt.live-analyze",
            name = "enabled",
            havingValue = "true")
    public LiveAnalyzeTrigger liveAnalyzeTrigger(
            final AudioGatewayProperties props,
            @org.springframework.beans.factory.annotation.Qualifier("meetingAiLiveAnalyzeWebClient")
            final WebClient meetingAiLiveAnalyzeWebClient,
            final MeterRegistry meters) {
        final AudioGatewayProperties.DirectStt.LiveAnalyze cfg =
                props.getDirectStt().getLiveAnalyze();
        return new LiveAnalyzeTrigger(
                meetingAiLiveAnalyzeWebClient,
                cfg.getSegmentWindow(),
                cfg.getBearerToken(),
                Duration.ofMillis(cfg.getTimeoutMs()),
                meters);
    }

    /**
     * Faz 24 İ4 — decorator sink: forwards to the base durable sink first
     * (Redis stream), then feeds the live-analyze aggregator best-effort.
     *
     * <p>{@code @Primary} so it wins DI resolution over the base
     * {@link DirectSttTranscriptResultSink}. The base is looked up via an
     * {@link ObjectProvider} filter so we do not inject the decorator into
     * itself (Spring footgun mirror of the {@code AudioChunkDispatcher}
     * decorator above).
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "audio.gateway.direct-stt.live-analyze",
            name = "enabled",
            havingValue = "true")
    public DirectSttTranscriptResultSink liveAnalyzeTriggerSink(
            final ObjectProvider<DirectSttTranscriptResultSink> sinkProvider,
            final LiveAnalyzeTrigger trigger) {
        final DirectSttTranscriptResultSink base = sinkProvider
                .orderedStream()
                .filter(s -> !(s instanceof LiveAnalyzeTriggerSink))
                .filter(s -> !(s instanceof LiveTranscriptBroadcastSink))
                .findFirst()
                .orElse(DirectSttTranscriptResultSink.noop());
        return new LiveAnalyzeTriggerSink(base, trigger);
    }

    /**
     * Faz 24 İ2-T — live transcript SSE broadcast hub. Multi-viewer pub/sub
     * across web clients, ephemeral, drop-oldest under back-pressure.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "audio.gateway.direct-stt.live-transcript",
            name = "broadcast-enabled",
            havingValue = "true")
    public LiveTranscriptStreamHub liveTranscriptStreamHub() {
        return new LiveTranscriptStreamHub();
    }

    /**
     * Faz 24 İ2-T — broadcast decorator, sits at the OUTSIDE of the sink chain
     * so publish happens after durable + live-analyze have executed.
     *
     * <p>Order in the chain (outer → inner): broadcast → live-analyze → durable
     * (Redis). A broadcast failure never masks the already-committed durable
     * emission; a durable failure is surfaced to the recorder untouched.
     *
     * <p>{@code @Primary} so it wins DI resolution. The base is looked up via an
     * {@link ObjectProvider} filter so we never inject the decorator into
     * itself and never inject the outer decorator into an inner decorator.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "audio.gateway.direct-stt.live-transcript",
            name = "broadcast-enabled",
            havingValue = "true")
    public DirectSttTranscriptResultSink liveTranscriptBroadcastSink(
            final ObjectProvider<DirectSttTranscriptResultSink> sinkProvider,
            final LiveTranscriptStreamHub hub) {
        final DirectSttTranscriptResultSink base = sinkProvider
                .orderedStream()
                .filter(s -> !(s instanceof LiveTranscriptBroadcastSink))
                .findFirst()
                .orElse(DirectSttTranscriptResultSink.noop());
        return new LiveTranscriptBroadcastSink(base, hub);
    }

    /**
     * Faz 24 — sentence assembly, the OUTERMOST decorator.
     *
     * <p>Order in the chain (outer → inner): assembly → broadcast → live-analyze →
     * durable (Redis). Outermost is the only position that works: an assembled line has
     * to reach both the durable stream (so the desktop's event reader sees it) and the
     * broadcast hub (so web viewers see it), and only the outer delegate covers both.
     *
     * <p>{@link com.example.audiogateway.service.LiveAnalyzeTriggerSink} ignores
     * assembled lines, so meeting-ai still receives each sentence once.
     *
     * <p>The base is resolved by explicit preference rather than stream order: whichever
     * decorators are enabled, this one must wrap the outermost of them, and relying on
     * {@code orderedStream()} to happen to return that one first would be a coin flip.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "audio.gateway.direct-stt.sentence-assembly",
            name = "enabled",
            havingValue = "true")
    public DirectSttTranscriptResultSink sentenceAssemblingSink(
            final ObjectProvider<DirectSttTranscriptResultSink> sinkProvider,
            final AudioGatewayProperties props,
            final MeterRegistry meters) {
        final List<DirectSttTranscriptResultSink> candidates = sinkProvider
                .orderedStream()
                .filter(s -> !(s instanceof SentenceAssemblingSink))
                .toList();
        final DirectSttTranscriptResultSink base = candidates.stream()
                .filter(LiveTranscriptBroadcastSink.class::isInstance)
                .findFirst()
                .or(() -> candidates.stream().filter(LiveAnalyzeTriggerSink.class::isInstance).findFirst())
                .or(() -> candidates.stream().findFirst())
                .orElse(DirectSttTranscriptResultSink.noop());

        final AudioGatewayProperties.DirectStt.SentenceAssembly cfg =
                props.getDirectStt().getSentenceAssembly();
        return new SentenceAssemblingSink(
                base,
                new SentenceAssemblyPolicy(cfg.getMaxSpeechMs(), cfg.getMaxChars(), cfg.getIdleMs()),
                meters);
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
