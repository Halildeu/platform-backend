package com.example.auth.serviceauth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.example.auth.config.JwtProperties;

/**
 * Issues the one-shot MFA delivery grant (gitops#3212).
 *
 * <p>Signed with the same service key as service tokens — no new PKI — but
 * never cached: every grant carries its own {@code jti} and binds one exact
 * delivery. The notify side persists only the DERIVED evidence at submit
 * time, never the raw JWT, and refuses a replayed {@code jti}.
 */
@Component
public class MfaDeliveryGrantIssuer {

    /** Fixed purpose; a grant is not a general-purpose token. */
    public static final String PURPOSE = "mfa_otp";

    private final JwtProperties jwtProperties;
    private final ServiceJwtKeyProperties serviceJwtKeyProperties;
    private final JwtEncoder serviceJwtEncoder;
    private final MfaDeliveryGrantProperties props;

    public MfaDeliveryGrantIssuer(JwtProperties jwtProperties,
            ServiceJwtKeyProperties serviceJwtKeyProperties,
            JwtEncoder serviceJwtEncoder,
            MfaDeliveryGrantProperties props) {
        this.jwtProperties = jwtProperties;
        this.serviceJwtKeyProperties = serviceJwtKeyProperties;
        this.serviceJwtEncoder = serviceJwtEncoder;
        this.props = props;
    }

    public record GrantRequest(String clientId, String audience, String subject, String recipient,
            String channel, String topic, String template, String authSessionId) {}

    public String issue(GrantRequest request, Instant now) {
        Instant expiresAt = now.plusSeconds(props.getTtlSeconds());
        Instant deliverBefore = now.plusSeconds(props.getDeliverWithinSeconds());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(request.subject())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .audience(List.of(request.audience()))
                .claim("purpose", PURPOSE)
                .claim("client_id", request.clientId())
                .claim("recipient", request.recipient())
                .claim("channel", request.channel())
                .claim("topic", request.topic())
                .claim("template", request.template())
                .claim("auth_session_id", request.authSessionId())
                .claim("deliver_before", deliverBefore.getEpochSecond())
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(serviceJwtKeyProperties.getKeyId())
                .build();
        return serviceJwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
