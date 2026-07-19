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
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        long maximum = maximumFor(request.getRequestURI());
        long contentLength = request.getContentLengthLong();
        if (maximum < 0 || contentLength < 1 || contentLength > maximum) {
            writeFailure(response, "authority request body length is absent or outside its exact bound");
            return;
        }
        try {
            MediaType contentType = MediaType.parseMediaType(request.getContentType());
            if (!MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                writeFailure(response, "authority request content type must be application/json");
                return;
            }
        } catch (RuntimeException invalidContentType) {
            writeFailure(response, "authority request content type is invalid");
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

    private void writeFailure(HttpServletResponse response, String message) throws IOException {
        response.setStatus(400);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), new ViewOnlyAuthorityErrorResponse(
                "faz22.6.viewOnlyPreflightError.v1", UUID.randomUUID(),
                "REQUEST_SCHEMA_INVALID", message, false, 0, false));
    }
}
