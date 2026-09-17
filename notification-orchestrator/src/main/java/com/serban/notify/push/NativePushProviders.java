package com.serban.notify.push;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name = "notify.native-push.sender-enabled", havingValue = "true")
@EnableConfigurationProperties(NativePushProviders.Settings.class)
public class NativePushProviders {
    @ConfigurationProperties("notify.native-push")
    public record Settings(List<Provider> providers) {}
    public record Provider(String applicationId, String provider, String environment, String projectId,
        String credentialsFile, String keyId, String teamId, boolean sandbox) {}
    private final Map<String, NativePushHttpSender> senders;

    public NativePushProviders(Settings settings, NativePushScopePolicy policy) {
        var configured = new HashMap<String, NativePushHttpSender>();
        if (settings.providers() == null || settings.providers().isEmpty())
            throw new IllegalArgumentException("Native push sender configuration missing");
        for (Provider item : settings.providers()) {
            policy.requireAllowed(item.applicationId(), item.provider(), item.environment());
            String scope = scope(item.applicationId(), item.provider(), item.environment());
            try {
                NativePushHttpSender sender;
                if ("FCM".equals(item.provider())) {
                    ServiceAccountCredentials credentials;
                    try (var stream = Files.newInputStream(Path.of(item.credentialsFile()))) {
                        credentials = ServiceAccountCredentials.fromStream(stream);
                    }
                    if (!item.projectId().equals(credentials.getProjectId()))
                        throw new IllegalArgumentException("FCM project binding mismatch");
                    var scoped = credentials.createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));
                    Supplier<String> token = () -> {
                        synchronized (scoped) {
                            try { scoped.refreshIfExpired(); return scoped.getAccessToken().getTokenValue(); }
                            catch (Exception failure) { throw new IllegalStateException("FCM credential refresh failed"); }
                        }
                    };
                    sender = NativePushHttpSender.fcm(item.projectId(), token);
                } else {
                    String pem = Files.readString(Path.of(item.credentialsFile())).replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
                    var key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
                    sender = NativePushHttpSender.apns(item.applicationId(), item.sandbox(), apnsToken(key, item.keyId(), item.teamId()));
                }
                if (configured.putIfAbsent(scope, sender) != null) throw new IllegalArgumentException("Duplicate native push provider scope");
            } catch (Exception failure) {
                // Do not expose secret file content or paths in Spring startup exceptions.
                throw new IllegalArgumentException("Native push provider credentials/configuration invalid");
            }
        }
        senders = Map.copyOf(configured);
    }

    static Supplier<String> apnsToken(ECPrivateKey key, String keyId, String teamId) {
        if (keyId == null || teamId == null || !keyId.matches("[A-Z0-9]{10}") || !teamId.matches("[A-Z0-9]{10}"))
            throw new IllegalArgumentException("Invalid APNs identity");
        return new Supplier<>() {
            private Instant expires = Instant.EPOCH;
            private String cached;
            public synchronized String get() {
                Instant now = Instant.now();
                if (now.isBefore(expires)) return cached;
                try {
                    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(),
                        new JWTClaimsSet.Builder().issuer(teamId).issueTime(Date.from(now)).build());
                    jwt.sign(new ECDSASigner(key));
                    cached = jwt.serialize(); expires = now.plusSeconds(1200);
                    return cached;
                } catch (Exception failure) { throw new IllegalStateException("APNs credential signing failed"); }
            }
        };
    }
    public NativePushHttpSender sender(String app, String provider, String environment) {
        return senders.get(scope(app, provider, environment));
    }
    private static String scope(String app, String provider, String environment) { return app + "/" + provider + "/" + environment; }
}
