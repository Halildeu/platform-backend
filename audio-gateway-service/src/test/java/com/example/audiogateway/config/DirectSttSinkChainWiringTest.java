package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.service.DirectSttTranscriptResultSink;
import com.example.audiogateway.service.SentenceAssemblingSink;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring wiring proof for the transcript-result sink chain (Faz 24 — transcript
 * readability).
 *
 * <p><b>The regression this exists for.</b> Each decorator used to be its own
 * {@code @Bean @Primary DirectSttTranscriptResultSink}. With more than one feature flag
 * on, Spring saw several primary candidates for a single injection point and the context
 * failed to start with {@code NoUniqueBeanDefinitionException} — a defect that only
 * appears when flags are combined, which is exactly what unit tests around the sinks
 * could not catch (Codex {@code 019f869d} post-impl REVISE point 4). These variants boot
 * the real context in every combination so the failure cannot come back unnoticed.
 *
 * <p>No live Redis is needed: {@code StringRedisTemplate} connects lazily.
 */
class DirectSttSinkChainWiringTest {

    private static final String ISSUER =
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test";
    private static final String DIRECT_STT = "audio.gateway.direct-stt.enabled=true";
    private static final String RESULT_STREAM =
            "audio.gateway.direct-stt.transcript-result-stream.enabled=true";
    private static final String TRANSCRIBE_URL =
            "audio.gateway.direct-stt.transcribe-url=http://localhost:18999/transcribe";
    private static final String ASSEMBLY = "audio.gateway.direct-stt.sentence-assembly.enabled=true";
    private static final String BROADCAST =
            "audio.gateway.direct-stt.live-transcript.broadcast-enabled=true";
    private static final String ANALYZE = "audio.gateway.direct-stt.live-analyze.enabled=true";
    private static final String ANALYZE_URL =
            "audio.gateway.direct-stt.live-analyze.base-url=http://localhost:18998";

    /** Exactly one bean may satisfy the single {@code DirectSttTranscriptResultSink} injection. */
    private static void assertSingleSinkInjectionPoint(final ApplicationContext ctx) {
        assertThat(ctx.getBean(DirectSttTranscriptResultSink.class))
                .as("a single sink must resolve without NoUniqueBeanDefinitionException")
                .isNotNull();
    }

    /** Assembly off: the chain head is whatever the other flags composed, never the assembler. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {ISSUER, DIRECT_STT, RESULT_STREAM, TRANSCRIBE_URL})
    class AssemblyDisabled {

        @Autowired private ApplicationContext ctx;
        @Autowired private DirectSttTranscriptResultSink sink;

        @Test
        void chainHeadIsNotTheAssembler() {
            assertSingleSinkInjectionPoint(ctx);
            assertThat(sink).isNotInstanceOf(SentenceAssemblingSink.class);
        }
    }

    /** Assembly only. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {ISSUER, DIRECT_STT, RESULT_STREAM, TRANSCRIBE_URL, ASSEMBLY})
    class AssemblyOnly {

        @Autowired private ApplicationContext ctx;
        @Autowired private DirectSttTranscriptResultSink sink;

        @Test
        void assemblerIsTheChainHead() {
            assertSingleSinkInjectionPoint(ctx);
            assertThat(sink).isInstanceOf(SentenceAssemblingSink.class);
        }
    }

    /** Assembly + broadcast — two features that each used to claim {@code @Primary}. */
    @Nested
    @SpringBootTest
    @TestPropertySource(
            properties = {ISSUER, DIRECT_STT, RESULT_STREAM, TRANSCRIBE_URL, ASSEMBLY, BROADCAST})
    class AssemblyAndBroadcast {

        @Autowired private ApplicationContext ctx;
        @Autowired private DirectSttTranscriptResultSink sink;

        @Test
        void assemblerStaysOutermost() {
            assertSingleSinkInjectionPoint(ctx);
            assertThat(sink).isInstanceOf(SentenceAssemblingSink.class);
        }
    }

    /** Assembly + live-analyze. */
    @Nested
    @SpringBootTest
    @TestPropertySource(
            properties = {
                ISSUER, DIRECT_STT, RESULT_STREAM, TRANSCRIBE_URL, ASSEMBLY, ANALYZE, ANALYZE_URL
            })
    class AssemblyAndAnalyze {

        @Autowired private ApplicationContext ctx;
        @Autowired private DirectSttTranscriptResultSink sink;

        @Test
        void assemblerStaysOutermost() {
            assertSingleSinkInjectionPoint(ctx);
            assertThat(sink).isInstanceOf(SentenceAssemblingSink.class);
        }
    }

    /**
     * All flags on — the combination that actually failed to start before the chain was
     * composed in one factory.
     */
    @Nested
    @SpringBootTest
    @TestPropertySource(
            properties = {
                ISSUER,
                DIRECT_STT,
                RESULT_STREAM,
                TRANSCRIBE_URL,
                ASSEMBLY,
                BROADCAST,
                ANALYZE,
                ANALYZE_URL
            })
    class AllFlagsOn {

        @Autowired private ApplicationContext ctx;
        @Autowired private DirectSttTranscriptResultSink sink;

        @Test
        void contextStartsAndAssemblerIsTheChainHead() {
            assertSingleSinkInjectionPoint(ctx);
            assertThat(sink).isInstanceOf(SentenceAssemblingSink.class);
        }
    }
}
