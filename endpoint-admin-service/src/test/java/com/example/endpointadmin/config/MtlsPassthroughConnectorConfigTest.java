package com.example.endpointadmin.config;

import com.example.endpointadmin.security.MtlsConnectorGuardFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 22.5 Step-2 — passthrough wiring conditionality (Codex {@code 019ec0f9}
 * P1/P2). Default-off must create NO guard registration (a disabled
 * FilterRegistrationBean with a null filter would throw at servlet startup), and
 * the enabled path must register the guard at highest precedence. Mutual
 * exclusion with forward-header fails the context.
 */
class MtlsPassthroughConnectorConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MtlsPassthroughConnectorConfig.class);

    private static String[] enabledProps(Path dir) throws IOException {
        Path ks = Files.writeString(dir.resolve("ks.p12"), "x");
        Path ts = Files.writeString(dir.resolve("ts.p12"), "x");
        return new String[]{
                "endpoint-admin.mtls.passthrough.enabled=true",
                "endpoint-admin.mtls.passthrough.port=8443",
                "endpoint-admin.mtls.passthrough.fixed-tenant-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "endpoint-admin.mtls.passthrough.key-store=" + ks,
                "endpoint-admin.mtls.passthrough.key-store-password=secret",
                "endpoint-admin.mtls.passthrough.trust-store=" + ts,
                "endpoint-admin.mtls.passthrough.trust-store-password=secret",
                "server.port=8096",
        };
    }

    @Test
    void disabled_contextStarts_andNoGuardRegistration() {
        runner.withPropertyValues("endpoint-admin.mtls.passthrough.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("mtlsConnectorGuardRegistration");
                });
    }

    @Test
    void enabled_registersGuardFilterAtHighestPrecedence(@TempDir Path dir) throws IOException {
        runner.withPropertyValues(enabledProps(dir))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("mtlsConnectorGuardRegistration");
                    FilterRegistrationBean<?> reg =
                            ctx.getBean("mtlsConnectorGuardRegistration", FilterRegistrationBean.class);
                    assertThat(reg.getFilter()).isInstanceOf(MtlsConnectorGuardFilter.class);
                    assertThat(reg.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
                    assertThat(reg.getUrlPatterns()).contains("/*");
                });
    }

    @Test
    void enabled_withForwardHeader_failsContext(@TempDir Path dir) throws IOException {
        String[] props = enabledProps(dir);
        String[] withForward = new String[props.length + 1];
        System.arraycopy(props, 0, withForward, 0, props.length);
        withForward[props.length] = "endpoint-admin.mtls.forward-header.enabled=true";
        runner.withPropertyValues(withForward)
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
