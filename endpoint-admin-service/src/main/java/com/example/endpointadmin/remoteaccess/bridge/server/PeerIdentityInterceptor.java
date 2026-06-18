package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.security.MachineCertExtractor;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — reads the mTLS peer certificate off the transport
 * ({@code Grpc.TRANSPORT_ATTR_SSL_SESSION}) into the {@link #PEER_IDENTITY} context key. The service layer
 * fail-closes on its absence (anonymous CONTROL/DATA is impossible while the server is enabled).
 *
 * <p>Real TLS configuration (certs, the passthrough L4 edge) is explicitly NOT this slice — it is pilot
 * infrastructure (T-4 + gitops). In-process tests inject a {@link PeerIdentity} directly via
 * {@link #PEER_IDENTITY} (the seam), exercising the same fail-closed paths.
 *
 * <p>The cert SAN → endpoint device-id extraction is intentionally NOT done here: B1.4's
 * {@code CertIdentityGuard} owns device-id parsing. The interceptor does extract the AD computer objectGUID
 * SAN URI when it is unambiguous, so the operator resolver can bind a live mTLS stream to a renewed AD CS
 * machine cert without trusting {@code AgentHello}. Transport identity is NOT device trust.
 */
public final class PeerIdentityInterceptor implements ServerInterceptor {

    private static final int SAN_TYPE_URI = 6;

    /** The authenticated transport identity of the calling stream; absent = anonymous = refuse. */
    public static final Context.Key<PeerIdentity> PEER_IDENTITY = Context.key("remote-bridge-peer-identity");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        SSLSession ssl = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        PeerIdentity identity = fromSslSession(ssl);
        if (identity == null) {
            // No authenticated peer — do NOT fail here (in-process tests inject via the context key);
            // the service layer refuses any stream whose context lacks an identity.
            return next.startCall(call, headers);
        }
        Context context = Context.current().withValue(PEER_IDENTITY, identity);
        return Contexts.interceptCall(context, call, headers, next);
    }

    /** Leaf-fingerprint transport key + full chain; null when there is no verified TLS peer. */
    static PeerIdentity fromSslSession(SSLSession ssl) {
        if (ssl == null) {
            return null;
        }
        try {
            Certificate[] peer = ssl.getPeerCertificates();
            if (peer == null || peer.length == 0 || !(peer[0] instanceof X509Certificate leaf)) {
                return null;
            }
            List<X509Certificate> chain = new ArrayList<>();
            for (Certificate certificate : peer) {
                if (certificate instanceof X509Certificate x509) {
                    chain.add(x509);
                }
            }
            return new PeerIdentity(fingerprint(leaf), Optional.empty(), certBoundAdComputerId(leaf), chain);
        } catch (SSLPeerUnverifiedException e) {
            return null; // unverified peer = anonymous = the service refuses
        }
    }

    private static Optional<String> certBoundAdComputerId(X509Certificate leaf) {
        try {
            Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
            if (sans == null) {
                return Optional.empty();
            }
            String match = null;
            int matchCount = 0;
            for (List<?> entry : sans) {
                if (entry.size() < 2) {
                    continue;
                }
                Object typeTag = entry.get(0);
                Object value = entry.get(1);
                if (!(typeTag instanceof Integer type) || type != SAN_TYPE_URI || !(value instanceof String uri)) {
                    continue;
                }
                Matcher matcher = MachineCertExtractor.SAN_URI_PATTERN.matcher(uri);
                if (matcher.matches()) {
                    match = matcher.group(1);
                    matchCount++;
                }
            }
            return matchCount == 1 ? Optional.of(match) : Optional.empty();
        } catch (CertificateParsingException e) {
            return Optional.empty();
        }
    }

    static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException("cannot fingerprint peer certificate", e);
        }
    }
}
