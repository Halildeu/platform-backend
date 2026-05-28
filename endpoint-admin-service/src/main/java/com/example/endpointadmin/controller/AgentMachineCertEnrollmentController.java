package com.example.endpointadmin.controller;

import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentRequest;
import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentResponse;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.cert.X509Certificate;
import java.util.UUID;

/**
 * Faz 22.3 — Backend mTLS self-enrollment endpoint.
 *
 * <p>Auth model: mTLS at the TLS layer. There is NO JWT / bearer for this
 * endpoint. The cert chain MUST be validated by the gateway / ingress before
 * the request reaches us; this controller picks the cert out of the standard
 * servlet attribute {@code jakarta.servlet.request.X509Certificate}.
 *
 * <p>Tenant resolution: the gateway is expected to inject {@code X-Tenant-Id}
 * after validating the cert/tenant binding. If absent we fall back to a
 * configured default — but production deployments MUST set the gateway
 * header (see ADR-0029 §"Backend layer").
 */
@RestController
@RequestMapping("/api/v1/endpoint-agent/endpoint-enrollments")
public class AgentMachineCertEnrollmentController {

    public static final String CERT_REQUEST_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final MachineCertAutoEnrollService service;
    private final String defaultTenantId;

    public AgentMachineCertEnrollmentController(
            MachineCertAutoEnrollService service,
            @Value("${endpoint-admin.mtls.default-tenant-id:}") String defaultTenantId) {
        this.service = service;
        this.defaultTenantId = defaultTenantId == null ? "" : defaultTenantId.trim();
    }

    @PostMapping("/auto")
    public ResponseEntity<AutoEnrollmentResponse> autoEnroll(
            @Valid @RequestBody AutoEnrollmentRequest request,
            HttpServletRequest servletRequest) {
        X509Certificate cert = resolveClientCert(servletRequest);
        UUID tenantId = resolveTenantId(servletRequest);

        MachineCertAutoEnrollService.Outcome outcome = service.autoEnroll(cert, tenantId, request);

        return ResponseEntity.status(outcome.status())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(outcome.body());
    }

    private X509Certificate resolveClientCert(HttpServletRequest request) {
        Object attr = request.getAttribute(CERT_REQUEST_ATTRIBUTE);
        if (attr instanceof X509Certificate[] arr && arr.length > 0) {
            return arr[0];
        }
        if (attr instanceof X509Certificate single) {
            return single;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "MTLS_CERT_MISSING");
    }

    private UUID resolveTenantId(HttpServletRequest request) {
        String header = request.getHeader(TENANT_HEADER);
        if (header != null && !header.isBlank()) {
            try {
                return UUID.fromString(header.trim());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TENANT_HEADER_INVALID");
            }
        }
        if (!defaultTenantId.isEmpty()) {
            try {
                return UUID.fromString(defaultTenantId);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "TENANT_DEFAULT_MISCONFIGURED");
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TENANT_HEADER_REQUIRED");
    }
}
