package com.example.user.notify;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
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

    @Bean("pendingActivationNotificationExecutor")
    public Executor pendingActivationNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pending-activation-notify-");
        // Drop (and let the caller log) rather than block the publishing thread
        // if the small queue ever fills — this is a best-effort convenience email.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
