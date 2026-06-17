package com.example.endpointadmin.config;

import com.example.endpointadmin.controller.EndpointAgentStatusController;
import com.example.endpointadmin.security.AesGcmDeviceSecretProtector;
import com.example.endpointadmin.security.DeviceSecretProtector;
import com.example.endpointadmin.security.EndpointRequestNonceCleanupJob;
import com.example.endpointadmin.security.EnrollmentTokenHasher;
import com.example.endpointadmin.service.DomainOpsDispatchRecoveryJob;
import com.example.endpointadmin.service.EndpointAgentStatusService;
import com.example.endpointadmin.service.diff.DiffCacheBackfillService;
import com.example.endpointadmin.service.diff.DiffCacheBackfillWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PrimaryEndpointPlaneConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AesGcmDeviceSecretProtector.class, EnrollmentTokenHasher.class);

    @Test
    void disabledPrimaryPlaneDoesNotRequireDeviceSecretEncryptionKey() {
        runner.withUserConfiguration(EndpointAgentStatusService.class, EndpointAgentStatusController.class)
                .withPropertyValues("endpoint-admin.primary-plane.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeviceSecretProtector.class);
                    assertThat(context).doesNotHaveBean(AesGcmDeviceSecretProtector.class);
                    assertThat(context).doesNotHaveBean(EnrollmentTokenHasher.class);
                    assertThat(context).doesNotHaveBean(EndpointAgentStatusService.class);
                    assertThat(context).doesNotHaveBean(EndpointAgentStatusController.class);
                });
    }

    @Test
    void disabledPrimaryPlaneDoesNotCreatePrimaryPlaneScheduledJobs() {
        runner.withUserConfiguration(EndpointRequestNonceCleanupJob.class, DomainOpsDispatchRecoveryJob.class,
                        DiffCacheBackfillWorker.class)
                .withPropertyValues("endpoint-admin.primary-plane.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EndpointRequestNonceCleanupJob.class);
                    assertThat(context).doesNotHaveBean(DomainOpsDispatchRecoveryJob.class);
                    assertThat(context).doesNotHaveBean(DiffCacheBackfillWorker.class);
                });
    }

    @Test
    void defaultPrimaryPlaneKeepsExistingDeviceSecretProtectorBehavior() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeviceSecretProtector.class);
                    assertThat(context).hasSingleBean(AesGcmDeviceSecretProtector.class);
                    assertThat(context).hasSingleBean(EnrollmentTokenHasher.class);
                });
    }

    @Test
    void defaultPrimaryPlaneKeepsDiffCacheBackfillWorkerWhenBackfillIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(DiffCacheBackfillWorker.class)
                .withBean(DiffCacheBackfillService.class, () -> mock(DiffCacheBackfillService.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DiffCacheBackfillWorker.class);
                });
    }
}
