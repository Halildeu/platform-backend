package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EthicsAuthorizationTest {
    private final OpenFgaAuthzService openFga = mock(OpenFgaAuthzService.class);
    private final OpenFgaProperties properties = enabledProperties();
    private final EthicsAuthorization authorization = new EthicsAuthorization(openFga, properties);
    private final StaffContext staff = new StaffContext(UUID.randomUUID(), "staff-test");

    @Test
    void denyAndUnavailableBothFailClosed() {
        when(openFga.check(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        assertThat(authorization.can(staff, "case_viewer", UUID.randomUUID())).isFalse();
        verify(openFga).check(
                staff.subject(),
                "case_viewer",
                EthicsAuthorization.PRODUCT_OBJECT,
                staff.orgId().toString());
        when(openFga.check(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic outage"));
        assertThat(authorization.can(staff, "case_viewer", UUID.randomUUID())).isFalse();
    }

    private static OpenFgaProperties enabledProperties() {
        var value = new OpenFgaProperties();
        value.setEnabled(true);
        value.setStoreId("test-store");
        value.setModelId("test-model");
        return value;
    }
}
