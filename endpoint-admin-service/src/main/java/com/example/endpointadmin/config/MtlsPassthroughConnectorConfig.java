package com.example.endpointadmin.config;

import com.example.endpointadmin.security.MtlsConnectorGuardFilter;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Faz 22.5 Step-2 — passthrough mTLS wiring (ADR-0029 #1501; Codex plan-time
 * thread {@code 019ec0f9} AGREE).
 *
 * <p>Adds, ONLY when {@code endpoint-admin.mtls.passthrough.enabled=true}:
 * <ul>
 *   <li>a secondary Tomcat connector on {@code passthrough.port} with
 *       {@code clientAuth=NEED} (servlet X509 identity), applied to the PRIMARY
 *       app server only — guarded by {@code factory.getPort()==server.port} so
 *       the management context never gets a second {@code :8443} (Codex #3);</li>
 *   <li>the bidirectional {@link MtlsConnectorGuardFilter} at HIGHEST
 *       precedence (profile-independent — runs before Spring Security);</li>
 *   <li>a fail-closed {@link MtlsPassthroughValidator}.</li>
 * </ul>
 * All beans are inert when disabled (default), so merging this is
 * zero-runtime-impact until an overlay enables passthrough.
 */
@Configuration
@EnableConfigurationProperties(MtlsPassthroughProperties.class)
public class MtlsPassthroughConnectorConfig {

    private static final Logger log = LoggerFactory.getLogger(MtlsPassthroughConnectorConfig.class);
    private static final String HTTP11_NIO = "org.apache.coyote.http11.Http11NioProtocol";

    @Bean
    MtlsPassthroughValidator mtlsPassthroughValidator(
            MtlsPassthroughProperties props,
            @Value("${endpoint-admin.mtls.forward-header.enabled:false}") boolean forwardHeaderEnabled,
            @Value("${server.port:8096}") int serverPort,
            @Value("${management.server.port:${server.port:8096}}") int managementPort) {
        return new MtlsPassthroughValidator(props, forwardHeaderEnabled, serverPort, managementPort);
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> mtlsPassthroughConnectorCustomizer(
            MtlsPassthroughProperties props,
            @Value("${server.port:8096}") int serverPort) {
        return factory -> {
            if (!props.isEnabled()) {
                return;
            }
            // Codex 019ec0f9 #3: apply ONLY to the primary app server. The
            // management context (management.server.port) uses a separate factory;
            // adding the :8443 connector there would attempt a second bind.
            if (factory.getPort() != serverPort) {
                return;
            }
            factory.addAdditionalTomcatConnectors(buildMtlsConnector(props));
            log.info("Added passthrough mTLS connector on port {} (clientAuth=NEED).", props.getPort());
        };
    }

    /**
     * Codex 019ec0f9 P1: the registration bean is created ONLY when passthrough
     * is enabled. A <em>disabled</em> {@link FilterRegistrationBean} with a null
     * filter throws in {@code RegistrationBean.onStartup()} →
     * {@code getDescription()} (which asserts a non-null filter, before the
     * {@code isEnabled()} check), so default-off must register NO filter at all
     * rather than a disabled one — preserving true zero-runtime-impact.
     */
    @Bean
    @ConditionalOnProperty(prefix = "endpoint-admin.mtls.passthrough",
            name = "enabled", havingValue = "true")
    FilterRegistrationBean<MtlsConnectorGuardFilter> mtlsConnectorGuardRegistration(
            MtlsPassthroughProperties props) {
        FilterRegistrationBean<MtlsConnectorGuardFilter> reg =
                new FilterRegistrationBean<>(new MtlsConnectorGuardFilter(props.getPort()));
        reg.setName("mtlsConnectorGuardFilter");
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    private Connector buildMtlsConnector(MtlsPassthroughProperties props) {
        Connector connector = new Connector(HTTP11_NIO);
        connector.setPort(props.getPort());
        connector.setScheme("https");
        connector.setSecure(true);

        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);

        SSLHostConfig sslHostConfig = new SSLHostConfig();
        // clientAuth = NEED — passthrough ALWAYS requires a client cert (Codex 019ec0f9).
        sslHostConfig.setCertificateVerification("required");

        SSLHostConfigCertificate certificate =
                new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
        certificate.setCertificateKeystoreFile(props.getKeyStore());
        certificate.setCertificateKeystorePassword(props.getKeyStorePassword());
        certificate.setCertificateKeystoreType(props.getKeyStoreType());
        sslHostConfig.addCertificate(certificate);

        sslHostConfig.setTruststoreFile(props.getTrustStore());
        sslHostConfig.setTruststorePassword(props.getTrustStorePassword());
        sslHostConfig.setTruststoreType(props.getTrustStoreType());

        connector.addSslHostConfig(sslHostConfig);
        return connector;
    }
}
