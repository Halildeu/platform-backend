package com.example.permission.dataaccess;

import com.example.permission.controller.AccessScopeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 21.3 PR-F (C3c) — companion to {@link DataAccessIntegrationTest} that
 * does NOT spin up Postgres. Asserts the dual-DS activation contract:
 *
 * <p>When {@code spring.datasource.reports-db.enabled=false} (this test
 * overrides the integration profile's default), the multi-name
 * {@code @ConditionalOnProperty} on {@link AccessScopeService} (PR-D iter-2
 * BLOCKER fix) denies the bean — the service is intentionally absent. The
 * {@link AccessScopeController} stays registered though, because its
 * dependency is {@code Optional<AccessScopeService>}, so Spring resolves it
 * to {@link java.util.Optional#empty()} and the controller's three endpoints
 * each short-circuit to {@code 503 SERVICE_UNAVAILABLE} — the contract that
 * the unit-level controller test ({@code AccessScopeControllerTest}) verifies
 * via direct constructor instantiation.
 *
 * <p>The point of this integration-level companion is to prove that the
 * <em>bean wiring</em> agrees with the unit-level direct-call test: the
 * controller really is registered when the service is not, and the property
 * gate really does deny the service. Catches the regression where
 * {@code @ConditionalOnBean(DataAccessScopeTupleWriter.class)} (iter-1
 * MAJOR-1, superseded by iter-2 BLOCKER) was order-dependent and could
 * deny the service in environments where it should have been live.
 */
@SpringBootTest(properties = {
        "spring.datasource.reports-db.enabled=false",
        "erp.openfga.enabled=true"
})
@ActiveProfiles("integration")
class DataAccessActivationContractTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void serviceAbsent_whenReportsDbDisabled_butControllerStillRegistered() {
        assertThat(context.getBeansOfType(AccessScopeService.class))
                .as("AccessScopeService must be absent when reports-db is disabled")
                .isEmpty();
        assertThat(context.getBeansOfType(AccessScopeController.class))
                .as("AccessScopeController must stay registered "
                        + "(receives Optional.empty() and short-circuits to 503)")
                .hasSize(1);
    }
}
