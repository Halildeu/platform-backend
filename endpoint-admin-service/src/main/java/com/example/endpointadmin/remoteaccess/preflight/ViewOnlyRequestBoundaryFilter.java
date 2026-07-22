package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects unknown-length or oversized authority bodies before Spring allocates a byte array. */
public final class ViewOnlyRequestBoundaryFilter extends OncePerRequestFilter {
    private static final String ROOT = "/api/v1/endpoint-admin/remote-access/preflight";
    private final ObjectMapper mapper;

    public ViewOnlyRequestBoundaryFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || maximumFor(request.getRequestURI()) < 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long maximum = maximumFor(request.getRequestURI());
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            writeFailure(response, 411, "CONTENT_LENGTH_REQUIRED",
                    "authority request Content-Length is required");
            return;
        }
        if (contentLength == 0) {
            writeFailure(response, 400, "REQUEST_SCHEMA_INVALID",
                    "authority request body must not be empty");
            return;
        }
        if (contentLength > maximum) {
            writeFailure(response, 413, "REQUEST_BODY_TOO_LARGE",
                    "authority request body exceeds its exact bound");
            return;
        }
        try {
            MediaType contentType = MediaType.parseMediaType(request.getContentType());
            if (!MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                writeFailure(response, 415, "CONTENT_TYPE_UNSUPPORTED",
                        "authority request content type must be application/json");
                return;
            }
        } catch (RuntimeException invalidContentType) {
            writeFailure(response, 415, "CONTENT_TYPE_UNSUPPORTED",
                    "authority request content type is invalid");
            return;
        }
        chain.doFilter(request, response);
    }

    private static long maximumFor(String uri) {
        if ((ROOT + "/attest").equals(uri)) {
            return 262_144;
        }
        if ((ROOT + "/checkpoint-leases/redeem").equals(uri)) {
            return ViewOnlyLeaseRedeemVerifier.MAX_REQUEST_BYTES;
        }
        if ((ROOT + "/checkpoints").equals(uri)) {
            return ViewOnlyCheckpointCreateVerifier.MAX_REQUEST_BYTES;
        }
        return -1;
    }

    private void writeFailure(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), new ViewOnlyAuthorityErrorResponse(
                "faz22.6.viewOnlyPreflightError.v1", UUID.randomUUID(),
                code, message, false, 0, false));
    }
}
