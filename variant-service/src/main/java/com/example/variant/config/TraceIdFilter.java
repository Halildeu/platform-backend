package com.example.variant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Ensures every request carries a traceId for log correlation.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String existing = request.getHeader(HEADER_TRACE_ID);
        if (existing == null || existing.isBlank()) {
            existing = request.getHeader(TRACE_ID);
        }
        String traceId = (existing == null || existing.isBlank()) ? UUID.randomUUID().toString() : existing;
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
