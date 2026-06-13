package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorCredential;
import jakarta.servlet.http.HttpServletRequest;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * Faz 22.6 slice-4c-2a (Codex 019ebe06) — extracts an {@link OperatorCredential} from an operator HTTP
 * request, the wire-side input to the {@link OperatorAuthenticator}. Two sources, mirroring the two real
 * authenticators: the mTLS client-cert chain the servlet container exposes after the TLS handshake, and an
 * {@code Authorization: Bearer} token. Pure/total/fail-closed: a null request or no cert + no bearer yields
 * {@link OperatorCredential#none()} (which authenticates to nothing).
 */
public final class OperatorCredentialExtractor {

    /** The standard servlet attribute the container sets for a validated client-cert chain. */
    static final String X509_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private OperatorCredentialExtractor() {
    }

    public static OperatorCredential extract(HttpServletRequest request) {
        if (request == null) {
            return OperatorCredential.none();
        }
        return new OperatorCredential(extractClientCertChain(request), extractBearerToken(request));
    }

    private static List<X509Certificate> extractClientCertChain(HttpServletRequest request) {
        Object attribute = request.getAttribute(X509_ATTRIBUTE);
        if (attribute instanceof X509Certificate[] chain && chain.length > 0) {
            return List.of(chain);
        }
        return List.of();
    }

    private static Optional<String> extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (!token.isBlank()) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }
}
