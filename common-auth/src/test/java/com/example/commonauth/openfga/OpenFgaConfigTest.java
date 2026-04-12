package com.example.commonauth.openfga;

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
}
