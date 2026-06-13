package com.example.endpointadmin.remoteaccess.bridge.server;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * Faz 22.6 slice-4c-1 (Codex 019ebe06) — authenticates the OPERATOR presenting a request to the operator-
 * facing transport (the live slice-4c-2 REST endpoint), the operator-side analogue of the agent-facing
 * {@link PeerIdentity}/{@code PeerIdentityInterceptor}. The seam answers "who is this operator, and is the
 * identity actually verified?" so the transport can refuse before calling any broker/step-up handler:
 * <b>no verified operator identity ⇒ no handler is invoked</b> (Codex S1 contract).
 *
 * <p><b>Foundation slice:</b> interface + value objects + an in-memory REFERENCE authenticator. The real
 * verifiers — an operator mTLS client-cert chain validated to an operator CA, or a JWT validated against the
 * IdP — are later slices; this seam keeps the transport testable and fail-closed before the live operator
 * channel exists.
 *
 * <p><b>Fail-closed:</b> a missing/blank/invalid credential yields {@link OperatorIdentity#unauthenticated()}
 * ({@code authenticated=false}, no subject) — the authenticator never throws and never returns null.
 */
public interface OperatorAuthenticator {

    /** How the operator authenticated — kept on the identity for audit + policy. */
    enum AuthMethod {
        UNAUTHENTICATED,
        MTLS_CLIENT_CERT,
        JWT_BEARER
    }

    /**
     * The credential the operator transport presents — an mTLS client-cert chain OR a bearer token (exactly
     * one is expected; both empty ⇒ unauthenticated). A defensive copy of the chain is held.
     */
    record OperatorCredential(List<X509Certificate> clientCertChain, Optional<String> bearerToken) {
        public OperatorCredential {
            clientCertChain = clientCertChain == null ? List.of() : List.copyOf(clientCertChain);
            bearerToken = bearerToken == null ? Optional.empty() : bearerToken;
        }

        public static OperatorCredential none() {
            return new OperatorCredential(List.of(), Optional.empty());
        }
    }

    /**
     * The authenticated operator identity. {@code authenticated=false} is the fail-closed floor — a transport
     * MUST refuse every operation when it is false, and the subject is then meaningless.
     */
    record OperatorIdentity(String operatorSubject, AuthMethod authMethod, boolean authenticated) {
        public OperatorIdentity {
            // an authenticated identity MUST carry a real auth method — a null/UNAUTHENTICATED method with
            // authenticated=true is inconsistent (an attacker forging the flag), so normalize it to unauth
            // (Codex REVISE: the 4c-2 guard must never accept it)
            if (authenticated && (authMethod == null || authMethod == AuthMethod.UNAUTHENTICATED)) {
                authenticated = false;
            }
        }

        public boolean isAuthenticated() {
            return authenticated && operatorSubject != null && !operatorSubject.isBlank()
                    && authMethod != null && authMethod != AuthMethod.UNAUTHENTICATED;
        }

        /** The fail-closed unauthenticated identity — no subject, no method. */
        public static OperatorIdentity unauthenticated() {
            return new OperatorIdentity(null, AuthMethod.UNAUTHENTICATED, false);
        }

        public static OperatorIdentity of(String operatorSubject, AuthMethod authMethod) {
            return new OperatorIdentity(operatorSubject, authMethod, true);
        }
    }

    /**
     * Authenticate the operator credential. MUST be total + fail-closed: a missing/blank/invalid credential
     * yields {@link OperatorIdentity#unauthenticated()} (never throws, never null).
     */
    OperatorIdentity authenticate(OperatorCredential credential);
}
