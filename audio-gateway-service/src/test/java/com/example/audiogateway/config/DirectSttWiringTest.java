package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.service.AudioChunkDispatcher;
import com.example.audiogateway.service.DirectSttForwardingDispatcher;
import com.example.audiogateway.service.NoOpAudioChunkDispatcher;
import com.example.audiogateway.service.RedisStreamsAudioChunkDispatcher;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring wiring proof for the Faz 24 issue #182 direct-STT decorator — boots the real
 * application context and asserts the {@code @Primary} resolution in the three operative
 * flag combinations. This is the machine-enforced check behind the
 * {@link DirectSttConfig} two-primary collision fix (Codex {@code 019eeb5f} point 6) — not
 * just reasoned, actually started.
 *
 * <p>No live Redis is needed: {@code StringRedisTemplate} is created lazily and the
 * connection is not opened at context start, so the {@code mode=redis} variant wires the
 * dispatcher chain without a server.
 */
class DirectSttWiringTest {

    private static final String ISSUER =
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test";

    /** Flag OFF (default): the controller gets the base bean, decorator absent. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {ISSUER})
    class DirectSttDisabled {

        @Autowired
        private ApplicationContext ctx;

        @Autowired
        private AudioChunkDispatcher injectedDispatcher;

        @Test
        void decoratorBeanAbsentAndBaseInjected() {
            assertThat(ctx.getBeanNamesForType(DirectSttForwardingDispatcher.class))
                    .as("decorator bean must NOT exist when direct-stt is disabled")
                    .isEmpty();
            // Default mode=noop → NoOp is the (only) dispatcher injected.
            assertThat(injectedDispatcher).isInstanceOf(NoOpAudioChunkDispatcher.class);
        }
    }

    /** Flag ON + mode=noop: decorator is @Primary and wraps NoOp. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
            ISSUER,
            "audio.gateway.direct-stt.enabled=true",
            "audio.gateway.direct-stt.transcript-result-stream.enabled=true",
            "audio.gateway.direct-stt.transcribe-url=http://localhost:18999/transcribe"
    })
    class DirectSttEnabledNoopMode {

        @Autowired
        private AudioChunkDispatcher injectedDispatcher;

        @Test
        void decoratorIsPrimaryAndInjected() {
            assertThat(injectedDispatcher)
                    .as("with direct-stt enabled the decorator must be the injected @Primary")
                    .isInstanceOf(DirectSttForwardingDispatcher.class);
        }
    }

    /**
     * Flag ON + mode=redis: BOTH RedisStreamsAudioChunkDispatcher (@Primary) and the
     * decorator (@Primary) exist; the BeanFactoryPostProcessor demotes Redis so the single
     * injection point resolves the decorator WITHOUT NoUniqueBeanDefinitionException.
     */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
            ISSUER,
            "audio.gateway.dispatcher.mode=redis",
            "audio.gateway.direct-stt.enabled=true",
            "audio.gateway.direct-stt.transcript-result-stream.enabled=true",
            "audio.gateway.direct-stt.transcribe-url=http://localhost:18999/transcribe"
    })
    class DirectSttEnabledRedisMode {

        @Autowired
        private ApplicationContext ctx;

        @Autowired
        private AudioChunkDispatcher injectedDispatcher;

        @Test
        void twoPrimaryCollisionResolvedToDecorator() {
            // Both base + decorator beans present.
            assertThat(ctx.getBeanNamesForType(RedisStreamsAudioChunkDispatcher.class))
                    .as("redis base dispatcher present in redis mode").isNotEmpty();
            assertThat(ctx.getBeanNamesForType(DirectSttForwardingDispatcher.class))
                    .as("decorator present when enabled").isNotEmpty();
            // The single-candidate injection resolves the decorator (no ambiguity exception).
            assertThat(injectedDispatcher).isInstanceOf(DirectSttForwardingDispatcher.class);
        }
    }
}
