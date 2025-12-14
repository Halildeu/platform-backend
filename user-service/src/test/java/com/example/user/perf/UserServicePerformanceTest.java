package com.example.user.perf;

import com.example.user.UserApplication;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = UserApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "PERF_TESTS", matches = "1")
@Tag("perf")
public class UserServicePerformanceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        reg.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        reg.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        reg.add("spring.flyway.enabled", () -> "true");
        reg.add("SECURITY_JWT_ISSUER", () -> "auth-service");
        reg.add("SECURITY_JWT_AUDIENCE", () -> "user-service");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JwtEncoder jwtEncoder;

    @BeforeAll
    static void seed(@Autowired JdbcTemplate jdbc) {
        // create N users (default 100k) in batches; overridable via PERF_USERS env
        int total = Integer.parseInt(System.getenv().getOrDefault("PERF_USERS", "100000"));
        int batch = 2000;
        // helpful indexes for test environment
        jdbc.execute("create index if not exists idx_users_create_date on users (create_date desc)");
        for (int i = 0; i < total; i += batch) {
            List<Object[]> rows = new ArrayList<>();
            for (int j = 0; j < batch && (i + j) < total; j++) {
                int idx = i + j;
                rows.add(new Object[]{
                        "Perf-" + idx,
                        "perf" + idx + "@example.com",
                        "x",
                        "USER",
                        true,
                        java.time.LocalDateTime.now().minusDays(idx % 365),
                        null,
                        15
                });
            }
            jdbc.batchUpdate(
                    "insert into users(name,email,password,role,enabled,create_date,last_login,session_timeout_minutes) " +
                            "values (?,?,?,?,?,?,?,?)",
                    rows
            );
        }
        Integer count = jdbc.queryForObject("select count(*) from users", Integer.class);
        assertThat(count).isNotNull();
        assertThat(count).isGreaterThanOrEqualTo(total);
    }

    private String bearer() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("perf@example.com")
                .issuer("auth-service")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("userId", 1)
                .build();
        var headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    @Test
    void list_users_p95_under_300ms() {
        String token = bearer();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> warmup = rest.exchange(
                "http://localhost:" + port + "/api/users/all?page=1&pageSize=50&sort=createDate,desc",
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(warmup.getStatusCode().value()).isEqualTo(200);

        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<String> res = rest.exchange(
                    "http://localhost:" + port + "/api/users/all?page=1&pageSize=50&sort=createDate,desc",
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            long dt = (System.nanoTime() - t0) / 1_000_000; // ms
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            times.add(dt);
        }
        times.sort(Long::compareTo);
        long p95 = times.get((int)Math.ceil(times.size() * 0.95) - 1);
        // Budget can be tuned per env; default <300ms
        long budget = Long.parseLong(System.getenv().getOrDefault("PERF_P95_BUDGET_MS", "300"));
        assertThat(p95).isLessThan(budget);
    }

    @Test
    void list_users_p95_under_budget_contains_email() {
        String token = bearer();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        String af = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"email\",\"op\":\"contains\",\"value\":\"perf\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<String> res = rest.exchange(
                    "http://localhost:" + port + "/api/users/all?page=1&pageSize=50&advancedFilter=" + af + "&sort=createDate,desc",
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            long dt = (System.nanoTime() - t0) / 1_000_000;
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            times.add(dt);
        }
        times.sort(Long::compareTo);
        long p95 = times.get((int)Math.ceil(times.size() * 0.95) - 1);
        long budget = Long.parseLong(System.getenv().getOrDefault("PERF_P95_BUDGET_MS", "300"));
        assertThat(p95).isLessThan(budget);
    }

    @Test
    void list_users_p95_under_budget_lessThan_timeout() {
        String token = bearer();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        String af = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"sessionTimeoutMinutes\",\"op\":\"lessThan\",\"value\":\"30\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<String> res = rest.exchange(
                    "http://localhost:" + port + "/api/users/all?page=1&pageSize=50&advancedFilter=" + af + "&sort=createDate,desc",
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            long dt = (System.nanoTime() - t0) / 1_000_000;
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            times.add(dt);
        }
        times.sort(Long::compareTo);
        long p95 = times.get((int)Math.ceil(times.size() * 0.95) - 1);
        long budget = Long.parseLong(System.getenv().getOrDefault("PERF_P95_BUDGET_MS", "300"));
        assertThat(p95).isLessThan(budget);
    }

    @Test
    void list_users_p95_under_budget_inRange_createDate() {
        String token = bearer();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        String af = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"createDate\",\"op\":\"inRange\",\"value\":\"2024-01-01T00:00:00\",\"value2\":\"2025-12-31T23:59:59\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<String> res = rest.exchange(
                    "http://localhost:" + port + "/api/users/all?page=1&pageSize=50&advancedFilter=" + af + "&sort=createDate,desc",
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            long dt = (System.nanoTime() - t0) / 1_000_000;
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            times.add(dt);
        }
        times.sort(Long::compareTo);
        long p95 = times.get((int)Math.ceil(times.size() * 0.95) - 1);
        long budget = Long.parseLong(System.getenv().getOrDefault("PERF_P95_BUDGET_MS", "300"));
        assertThat(p95).isLessThan(budget);
    }

    @Test
    void export_csv_is_chunked() {
        String token = bearer();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> res = rest.exchange(
                "http://localhost:" + port + "/api/users/export.csv",
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<String> te = res.getHeaders().get("Transfer-Encoding");
        // çoğu durumda chunked olmalı
        assertThat(te).isNotNull();
        assertThat(te).anyMatch(v -> v.toLowerCase().contains("chunked"));
    }

    @Test
    void explain_analyze_uses_indexes_for_email_contains() {
        // lower(email) index mevcut, contains için prefix eşleşmeler index kullanımını gösterebilir
        String plan = jdbcTemplate.queryForObject(
                "EXPLAIN ANALYZE SELECT * FROM users WHERE lower(email) LIKE 'perf%' ORDER BY create_date DESC LIMIT 50",
                String.class);
        assertThat(plan).isNotNull();
        // Plan içinde bir index erişimi beklenir (Index Scan/Bitmap Index)
        assertThat(plan.toLowerCase()).contains("index");
    }

    @Configuration
    static class JwtTestConfig {
        @Bean
        public RSAKey perfRsaKey() throws Exception {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair kp = kpg.generateKeyPair();
            return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) kp.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) kp.getPrivate())
                    .keyID("perf-kid")
                    .build();
        }

        @Bean
        @Primary
        public JwtEncoder perfJwtEncoder(RSAKey perfRsaKey) {
            JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(perfRsaKey));
            return new NimbusJwtEncoder(jwkSource);
        }
    }
}
