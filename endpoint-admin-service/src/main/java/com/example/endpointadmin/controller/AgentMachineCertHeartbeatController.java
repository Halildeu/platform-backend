package com.example.endpointadmin.controller;

import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatRequest;
import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatResponse;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.EndpointHeartbeatService;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;

/**
 * Faz 22.5 M2-B — tokenless mTLS heartbeat surface.
 *
 * <p>This endpoint is served under {@code /api/v1/endpoint-agent/**}, so the
 * mTLS connector guard applies. It authenticates the presented machine cert and
 * then reuses the existing heartbeat persistence service with a synthetic
 * {@link DeviceCredentialResult}; no bearer token or HMAC device credential is
 * accepted on this path.
 */
@RestController
@RequestMapping("/api/v1/endpoint-agent")
public class AgentMachineCertHeartbeatController {

    private static final Logger log = LoggerFactory.getLogger(AgentMachineCertHeartbeatController.class);

    private final MachineCertAutoEnrollService certService;
    private final EndpointHeartbeatService heartbeatService;
    private final boolean forwardHeaderEnabled;
    private final boolean passthroughEnabled;
    private final int passthroughPort;
    private final String passthroughFixedTenantId;

    public AgentMachineCertHeartbeatController(
            MachineCertAutoEnrollService certService,
            EndpointHeartbeatService heartbeatService,
            @Value("${endpoint-admin.mtls.forward-header.enabled:false}") boolean forwardHeaderEnabled,
            @Value("${endpoint-admin.mtls.passthrough.enabled:false}") boolean passthroughEnabled,
            @Value("${endpoint-admin.mtls.passthrough.port:8443}") int passthroughPort,
            @Value("${endpoint-admin.mtls.passthrough.fixed-tenant-id:}") String passthroughFixedTenantId) {
        this.certService = certService;
        this.heartbeatService = heartbeatService;
        this.forwardHeaderEnabled = forwardHeaderEnabled;
        this.passthroughEnabled = passthroughEnabled;
        this.passthroughPort = passthroughPort;
        this.passthroughFixedTenantId = passthroughFixedTenantId;
    }

    @PostMapping("/heartbeat")
    public AgentHeartbeatResponse heartbeat(@Valid @RequestBody AgentHeartbeatRequest request,
                                            HttpServletRequest servletRequest) {
        X509Certificate cert = resolveClientCert(servletRequest);
        UUID tenantId = resolveTenantId(servletRequest);
        DeviceCredentialResult principal = certService.authenticateLifecycle(cert, tenantId);
        return heartbeatService.recordHeartbeat(principal, request, resolveRemoteAddress(servletRequest));
    }

    private X509Certificate resolveClientCert(HttpServletRequest request) {
        Object attr = request.getAttribute(AgentMachineCertEnrollmentController.CERT_REQUEST_ATTRIBUTE);
        if (attr instanceof X509Certificate[] arr && arr.length > 0) {
            return arr[0];
        }
        if (attr instanceof X509Certificate single) {
            return single;
        }

        if (forwardHeaderEnabled) {
            String pemHeader = request.getHeader(AgentMachineCertEnrollmentController.CERT_FORWARD_HEADER);
            if (pemHeader != null && !pemHeader.isBlank()) {
                return parseForwardedPem(pemHeader);
            }
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MTLS_CERT_MISSING");
    }

    private X509Certificate parseForwardedPem(String pemHeader) {
        try {
            String pem = URLDecoder.decode(pemHeader, StandardCharsets.UTF_8);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream in = new ByteArrayInputStream(
                    pem.getBytes(StandardCharsets.UTF_8))) {
                return (X509Certificate) cf.generateCertificate(in);
            }
        } catch (CertificateException | java.io.IOException ex) {
            log.warn("Failed to parse forwarded X-Client-Cert header for heartbeat: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MTLS_CERT_FORWARD_INVALID");
        }
    }

    private UUID resolveTenantId(HttpServletRequest request) {
        if (passthroughEnabled && request.getLocalPort() == passthroughPort) {
            return UUID.fromString(passthroughFixedTenantId.trim());
        }
        String header = request.getHeader(AgentMachineCertEnrollmentController.TENANT_HEADER);
        if (header == null || header.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TENANT_HEADER_REQUIRED");
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TENANT_HEADER_INVALID");
        }
    }

    private String resolveRemoteAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
