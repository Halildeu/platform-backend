package com.example.gpcore;

import com.example.gpcore.authz.AuthorizationDecisionService;
import com.example.gpcore.gateway.ReadGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Skeleton wiring smoke: the Spring context boots with the enforcement kernel
 * wired (decision service + Read Gateway + fail-closed relationship checker over
 * the separate {@code gp.openfga} store) and the Wave-1 placeholder data ports.
 */
@SpringBootTest
class GpCoreApplicationTests {

    @Autowired
    ReadGateway readGateway;

    @Autowired
    AuthorizationDecisionService authorizationDecisionService;

    @Test
    void contextLoadsWithEnforcementKernelWired() {
        assertNotNull(readGateway, "ReadGateway bean present");
        assertNotNull(authorizationDecisionService, "AuthorizationDecisionService bean present");
    }
}
