package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ES-306 evidence — the 30-concurrent-POST load test showed 24×504 gateway
 * timeouts because the pod accepted burst faster than PG could persist.
 * With Resilience4j rate-limit + bulkhead in place the excess is now
 * refused fast with 429 (RATE_LIMITED / SERVICE_BUSY), never 503/504/500.
 *
 * The ceilings are dropped low so a unit test can exercise the boundary
 * deterministically. Each test method restarts the Spring context so the
 * in-process RateLimiter / Bulkhead state does not leak between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "resilience4j.ratelimiter.instances.publicIntake.limit-for-period=2",
        "resilience4j.ratelimiter.instances.publicIntake.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.publicIntake.timeout-duration=0",
        "resilience4j.bulkhead.instances.publicIntake.max-concurrent-calls=2",
        "resilience4j.bulkhead.instances.publicIntake.max-wait-duration=0",
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class EthicsRateLimitContractTest {
    private static final String CLIENT_SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static String legitimatePayload() {
        return "{\"mode\":\"ANONYMOUS\",\"category\":\"OTHER\",\"subject\":\"Sentetik konu\","
                + "\"description\":\"Sentetik anlatım.\",\"locale\":\"tr\","
                + "\"accessSecret\":\"" + CLIENT_SECRET + "\",\"noticeVersion\":\"tr-test-pilot-v1\"}";
    }

    @Test
    void burstBeyondRateLimitReturns429NotGatewayTimeout() throws Exception {
        // 5 sequential requests, ceiling 2 per 60s: first two land, remainder
        // must reject with RATE_LIMITED (never 500/503/504).
        List<Integer> statuses = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MvcResult result = mvc.perform(post("/api/v1/public/ethics/reports")
                            .header("Host", "etik.acik.com")
                            .header("Idempotency-Key", "burst-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(legitimatePayload()))
                    .andReturn();
            int status = result.getResponse().getStatus();
            statuses.add(status);
            if (status == 429) {
                codes.add(mapper.readTree(result.getResponse().getContentAsString()).at("/error/code").asText());
            }
        }

        assertThat(statuses).filteredOn(s -> s == 201).hasSizeGreaterThanOrEqualTo(1);
        assertThat(statuses).filteredOn(s -> s == 429).hasSizeGreaterThanOrEqualTo(2);
        assertThat(statuses).allSatisfy(s -> assertThat(s).isNotEqualTo(500).isNotEqualTo(503).isNotEqualTo(504));
        assertThat(codes).contains("RATE_LIMITED");
    }

    @Test
    void concurrentBurstBeyondBulkheadReturns429NotGatewayTimeout() throws Exception {
        int count = 6;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                final int idx = i;
                Callable<MvcResult> task = () -> {
                    start.await();
                    return mvc.perform(post("/api/v1/public/ethics/reports")
                                    .header("Host", "etik.acik.com")
                                    .header("Idempotency-Key", "concurrent-" + idx)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(legitimatePayload()))
                            .andReturn();
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                statuses.add(f.get(20, TimeUnit.SECONDS).getResponse().getStatus());
            }

            assertThat(statuses).allSatisfy(s -> assertThat(s).isNotEqualTo(500).isNotEqualTo(503).isNotEqualTo(504));
            assertThat(statuses).filteredOn(s -> s == 429).hasSizeGreaterThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
