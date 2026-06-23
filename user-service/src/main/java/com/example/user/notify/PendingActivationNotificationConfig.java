package com.example.user.notify;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * #734: enables async dispatch for the pending-activation admin email and
 * provides a small BOUNDED executor so a notification backlog/hiccup can never
 * starve threads or block the login/provisioning flow.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(PendingActivationNotificationProperties.class)
public class PendingActivationNotificationConfig {

    private static final Logger log = LoggerFactory.getLogger(PendingActivationNotificationConfig.class);

    @Bean("pendingActivationNotificationExecutor")
    public Executor pendingActivationNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pending-activation-notify-");
        // #734 (Codex 019ef41c REVISE): NON-THROWING reject handler. AbortPolicy
        // throws RejectedExecutionException, which could surface on the
        // after-commit callback path and affect the login/provisioning flow. This
        // is a best-effort convenience email, so a full queue silently drops the
        // task (logged) rather than throwing.
        executor.setRejectedExecutionHandler((r, ex) ->
                log.warn("#734 pending-activation notify queue full; dropping best-effort task"));
        executor.initialize();
        return executor;
    }
}
