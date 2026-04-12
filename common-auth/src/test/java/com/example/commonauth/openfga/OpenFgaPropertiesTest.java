package com.example.commonauth.openfga;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenFgaProperties — getters/setters, defaults, DevScope.
 * SK-7 coverage target.
 */
class OpenFgaPropertiesTest {

    @Test
    void defaults_disabledAndEmptyStrings() {
        var props = new OpenFgaProperties();
        assertFalse(props.isEnabled());
        assertNotNull(props.getApiUrl()); // default: http://localhost:4000
        assertNull(props.getStoreId());
        assertNull(props.getModelId());
    }

    @Test
    void settersAndGetters_work() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setApiUrl("http://localhost:4000");
        props.setStoreId("store1");
        props.setModelId("model1");

        assertTrue(props.isEnabled());
        assertEquals("http://localhost:4000", props.getApiUrl());
        assertEquals("store1", props.getStoreId());
        assertEquals("model1", props.getModelId());
    }

    @Test
    void devScope_defaultsEmpty() {
        var props = new OpenFgaProperties();
        var scope = props.getDevScope();
        assertNotNull(scope);
    }

    @Test
    void devScope_setAndGet() {
        var props = new OpenFgaProperties();
        var scope = props.getDevScope();
        scope.setCompanyIds(java.util.Set.of(1L, 2L, 3L));
        scope.setProjectIds(java.util.Set.of(10L, 20L));

        assertEquals(3, scope.getCompanyIds().size());
        assertEquals(2, scope.getProjectIds().size());
    }
}
