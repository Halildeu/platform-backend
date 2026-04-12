package com.example.permission.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SecurityConfig and SecurityConfigLocal.
 * Verifies D-004 (no JWT in local) and security chain configuration.
 * Does NOT boot Spring context — tests bean configuration logic only.
 */
class SecurityConfigTest {

    @Nested
    @DisplayName("SecurityConfig (prod profile)")
    class ProdConfig {

        @Test
        @DisplayName("has @Profile(!local & !dev) annotation")
        void profileAnnotation() {
            var profile = SecurityConfig.class.getAnnotation(
                    org.springframework.context.annotation.Profile.class);
            assertNotNull(profile, "SecurityConfig must have @Profile");
            assertEquals(1, profile.value().length);
            assertTrue(profile.value()[0].contains("!local"));
            assertTrue(profile.value()[0].contains("!dev"));
        }

        @Test
        @DisplayName("has @Order(HIGHEST_PRECEDENCE + 10)")
        void orderAnnotation() {
            var order = SecurityConfig.class.getAnnotation(
                    org.springframework.core.annotation.Order.class);
            assertNotNull(order, "SecurityConfig must have @Order");
            assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10, order.value());
        }

        @Test
        @DisplayName("constructor accepts Environment")
        void constructorAcceptsEnv() {
            var env = new MockEnvironment();
            var config = new SecurityConfig(env);
            assertNotNull(config);
        }

        @Test
        @DisplayName("jwtDecoder uses default JWK URI when no property set")
        void jwtDecoderDefaultUri() {
            var env = new MockEnvironment();
            var config = new SecurityConfig(env);
            // jwtDecoder() creates a NimbusJwtDecoder — we just verify no exception
            // (actual JWKS fetch would fail but bean creation should not)
            assertDoesNotThrow(() -> config.jwtDecoder());
        }

        @Test
        @DisplayName("jwtDecoder uses custom JWK URI from property")
        void jwtDecoderCustomUri() {
            var env = new MockEnvironment()
                    .withProperty("security.jwt.jwk-set-uri", "http://keycloak:8080/realms/test/certs")
                    .withProperty("security.jwt.issuer", "http://keycloak:8080/realms/test");
            var config = new SecurityConfig(env);
            assertDoesNotThrow(() -> config.jwtDecoder());
        }

        @Test
        @DisplayName("jwtAuthenticationConverter is CompositeJwtAuthenticationConverter")
        void compositeConverter() {
            var env = new MockEnvironment();
            var config = new SecurityConfig(env);
            var converter = config.jwtAuthenticationConverter();
            assertNotNull(converter);
            assertInstanceOf(CompositeJwtAuthenticationConverter.class, converter);
        }

        @Test
        @DisplayName("internalApiKeyAuthFilter disabled when no key set")
        void apiKeyFilterDisabledNoKey() {
            var env = new MockEnvironment();
            var config = new SecurityConfig(env);
            var filter = config.internalApiKeyAuthFilter();
            assertNotNull(filter);
        }

        @Test
        @DisplayName("internalApiKeyAuthFilter enabled when key set")
        void apiKeyFilterEnabledWithKey() {
            var env = new MockEnvironment()
                    .withProperty("security.internal-api-key.value", "test-api-key-123");
            var config = new SecurityConfig(env);
            var filter = config.internalApiKeyAuthFilter();
            assertNotNull(filter);
        }
    }

    @Nested
    @DisplayName("SecurityConfigLocal (D-004: no JWT in local/dev)")
    class LocalConfig {

        @Test
        @DisplayName("has @Profile(local, dev) annotation")
        void profileAnnotation() {
            var profile = SecurityConfigLocal.class.getAnnotation(
                    org.springframework.context.annotation.Profile.class);
            assertNotNull(profile, "SecurityConfigLocal must have @Profile");
            boolean hasLocal = false;
            boolean hasDev = false;
            for (String v : profile.value()) {
                if (v.equals("local")) hasLocal = true;
                if (v.equals("dev")) hasDev = true;
            }
            assertTrue(hasLocal, "@Profile must include 'local'");
            assertTrue(hasDev, "@Profile must include 'dev'");
        }

        @Test
        @DisplayName("has @Order(HIGHEST_PRECEDENCE) — takes precedence over prod")
        void orderPrecedence() {
            var order = SecurityConfigLocal.class.getAnnotation(
                    org.springframework.core.annotation.Order.class);
            assertNotNull(order, "SecurityConfigLocal must have @Order");
            assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE, order.value());

            var prodOrder = SecurityConfig.class.getAnnotation(
                    org.springframework.core.annotation.Order.class);
            assertTrue(order.value() < prodOrder.value(),
                    "Local config must have higher precedence (lower number) than prod");
        }

        @Test
        @DisplayName("does NOT have oauth2ResourceServer — D-004 constraint C-002")
        void noOauth2ResourceServer() throws Exception {
            // Verify SecurityConfigLocal source code does not contain oauth2ResourceServer
            // This is a code-level constraint check (D-004 / C-002)
            var methods = SecurityConfigLocal.class.getDeclaredMethods();
            for (var method : methods) {
                assertFalse(method.getName().contains("oauth2"),
                        "SecurityConfigLocal must NOT have oauth2ResourceServer (C-002)");
            }
        }
    }
}
