package com.example.permission;

import com.example.permission.controller.AuthorizationControllerV1;
import com.example.permission.service.AuthorizationQueryService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for platform-k8s-gitops#3198.
 *
 * <p>{@code /authz/me} is an orchestration path containing identity-directory and OpenFGA network
 * calls. A transaction on the controller held one Hikari connection for the whole remote-call
 * duration. Five concurrent requests therefore occupied the complete TEST pool and made
 * user-service time out while deriving its authorization context.
 */
class AuthorizationTransactionBoundaryTest {

    @Test
    @DisplayName("/authz/me controller does not hold a database transaction across remote calls")
    void authzMeOrchestrationIsNotTransactional() throws Exception {
        Method getMe = AuthorizationControllerV1.class.getMethod("getMe", Jwt.class);

        assertThat(getMe.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("scope summary suspends inherited transactions before OpenFGA calls")
    void scopeSummarySuspendsInheritedTransactions() throws Exception {
        Method singleSubject =
                AuthorizationQueryService.class.getMethod("getUserScopeSummary", Long.class);
        Method dualSubject =
                AuthorizationQueryService.class.getMethod(
                        "getUserScopeSummary", Long.class, String.class);

        assertNotSupported(singleSubject);
        assertNotSupported(dualSubject);
    }

    private static void assertNotSupported(Method method) {
        Transactional boundary = method.getAnnotation(Transactional.class);
        assertThat(boundary).isNotNull();
        assertThat(boundary.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }
}
