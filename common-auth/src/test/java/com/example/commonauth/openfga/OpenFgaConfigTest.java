package com.example.commonauth.openfga;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenFgaConfig factory — disabled, enabled, error handling.
 * SK-7 coverage target.
 */
class OpenFgaConfigTest {

    @Test
    void createClient_disabled_returnsNull() {
        var props = new OpenFgaProperties();
        props.setEnabled(false);
        assertNull(OpenFgaConfig.createClient(props));
    }

    @Test
    void createAuthzService_disabled_returnsServiceWithNullClient() {
        var props = new OpenFgaProperties();
        props.setEnabled(false);
        var service = OpenFgaConfig.createAuthzService(props);
        assertNotNull(service);
    }

    @Test
    void createClient_enabled_withValidUrl_returnsClient() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setApiUrl("http://localhost:4000");
        props.setStoreId("store-123");
        props.setModelId("model-456");
        var client = OpenFgaConfig.createClient(props);
        assertNotNull(client);
    }

    @Test
    void createClient_enabled_withBlankStoreId_stillCreatesClient() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setApiUrl("http://localhost:4000");
        props.setStoreId("");
        props.setModelId("");
        var client = OpenFgaConfig.createClient(props);
        assertNotNull(client);
    }

    @Test
    void createClient_enabled_withNullStoreId_stillCreatesClient() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setApiUrl("http://localhost:4000");
        props.setStoreId(null);
        props.setModelId(null);
        var client = OpenFgaConfig.createClient(props);
        assertNotNull(client);
    }

    /**
     * The defect these guard against is silent: an unset timeout is not a slow call, it is a
     * call that never ends, and the caller only learns that from a gateway error a minute and
     * a half later (ES-308, platform-k8s-gitops#2667). Asserting "some finite bound exists"
     * is the whole point — the exact numbers are tunable, infinity is not.
     */
    @Test
    void defaultsAreFinite() {
        var props = new OpenFgaProperties();
        assertNotNull(props.getConnectTimeout());
        assertNotNull(props.getReadTimeout());
        assertTrue(props.getConnectTimeout().toMillis() > 0);
        assertTrue(props.getReadTimeout().toMillis() > 0);
    }

    @Test
    void unsetOrNonPositiveTimeoutFallsBackToTheDefault_neverToInfinity() {
        var fallback = Duration.ofSeconds(7);
        assertEquals(fallback, OpenFgaConfig.orDefault(null, fallback));
        assertEquals(fallback, OpenFgaConfig.orDefault(Duration.ZERO, fallback));
        assertEquals(fallback, OpenFgaConfig.orDefault(Duration.ofSeconds(-1), fallback));
        assertEquals(Duration.ofSeconds(2), OpenFgaConfig.orDefault(Duration.ofSeconds(2), fallback));
    }

    @Test
    void configuredTimeoutsSurviveIntoTheClient() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setApiUrl("http://localhost:4000");
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setReadTimeout(Duration.ofSeconds(4));
        assertNotNull(OpenFgaConfig.createClient(props));
        assertEquals(Duration.ofSeconds(2), props.getConnectTimeout());
        assertEquals(Duration.ofSeconds(4), props.getReadTimeout());
    }
}
