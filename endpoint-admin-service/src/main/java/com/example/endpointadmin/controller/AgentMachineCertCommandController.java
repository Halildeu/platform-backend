package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResponse;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.EndpointAgentCommandService;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * Faz 22.5 M2-C — tokenless mTLS command lifecycle surface.
 *
 * <p>This mirrors the legacy HMAC-authenticated
 * {@code /api/v1/agent/commands/**} controller, but authenticates the device
 * by the presented machine certificate and then reuses the same command
 * service. Claim/result semantics, idempotency, result validation, audit, and
 * ingest side effects therefore stay single-sourced in
 * {@link EndpointAgentCommandService}.
 */
@RestController
@RequestMapping("/api/v1/endpoint-agent/commands")
@ConditionalOnPrimaryEndpointPlane
public class AgentMachineCertCommandController {

    private static final Logger log = LoggerFactory.getLogger(AgentMachineCertCommandController.class);

    private final MachineCertAutoEnrollService certService;
    private final EndpointAgentCommandService commandService;
    private final boolean forwardHeaderEnabled;

    // Same passthrough tenant-authority model as enrollment/heartbeat: when a
    // request lands on the dedicated mTLS connector, X-Tenant-Id is ignored and
    // the fixed single-tenant UUID is used. Runtime startup validation in
    // MtlsPassthroughValidator guarantees that this UUID is valid and that
    // passthrough mode is not combined with forwarded-header mode.
    private final boolean passthroughEnabled;
    private final int passthroughPort;
    private final String passthroughFixedTenantId;

    public AgentMachineCertCommandController(
            MachineCertAutoEnrollService certService,
            EndpointAgentCommandService commandService,
            @Value("${endpoint-admin.mtls.forward-header.enabled:false}") boolean forwardHeaderEnabled,
            @Value("${endpoint-admin.mtls.passthrough.enabled:false}") boolean passthroughEnabled,
            @Value("${endpoint-admin.mtls.passthrough.port:8443}") int passthroughPort,
            @Value("${endpoint-admin.mtls.passthrough.fixed-tenant-id:}") String passthroughFixedTenantId) {
        this.certService = certService;
        this.commandService = commandService;
        this.forwardHeaderEnabled = forwardHeaderEnabled;
        this.passthroughEnabled = passthroughEnabled;
        this.passthroughPort = passthroughPort;
        this.passthroughFixedTenantId = passthroughFixedTenantId;
    }

    @GetMapping("/next")
    public ResponseEntity<AgentCommandResponse> nextCommand(HttpServletRequest servletRequest) {
        DeviceCredentialResult principal = authenticate(servletRequest);
        return commandService.claimNext(principal)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{commandId}/result")
    public ResponseEntity<Void> submitResult(HttpServletRequest servletRequest,
                                             @PathVariable UUID commandId,
                                             @Valid @RequestBody AgentCommandResultRequest request) {
        DeviceCredentialResult principal = authenticate(servletRequest);
        commandService.submitResult(principal, commandId, request);
        return ResponseEntity.accepted().build();
    }

    private DeviceCredentialResult authenticate(HttpServletRequest request) {
        X509Certificate cert = resolveClientCert(request);
        UUID tenantId = resolveTenantId(request);
        return certService.authenticateLifecycle(cert, tenantId);
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
            log.warn("Failed to parse forwarded X-Client-Cert header for command lifecycle.");
            log.debug("Forwarded X-Client-Cert parse failure detail", ex);
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
}
