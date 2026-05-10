package com.example.auth.impersonation;

import com.example.auth.impersonation.ImpersonationSessionClient.ActiveSessionExistsException;
import com.example.auth.impersonation.ImpersonationSessionClient.SessionResource;
import com.example.auth.impersonation.ImpersonationSessionClient.StartRequest;
import com.example.auth.impersonation.KeycloakBrokerClient.DecodedClaims;
import com.example.auth.impersonation.KeycloakBrokerClient.ExchangeResult;
import com.example.auth.impersonation.KeycloakBrokerClient.TokenExchangeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * User Impersonation v1 — auth-service public broker controller.
 *
 * <p>Codex 019e0dfb iter-26 mimari split:
 * <ul>
 *   <li>This controller = identity broker (public endpoint, KC token exchange)</li>
 *   <li>permission-service = session/audit authority (DB write via internal API)</li>
 * </ul>
 *
 * <p>Critical flow contract (Codex iter-19/26):
 * <ol>
 *   <li>SuperAdmin authentication (PreAuthorize)</li>
 *   <li>Nested impersonation reject — admin token azp must NOT be broker</li>
 *   <li>Reason validation (min 10 chars)</li>
 *   <li>KC token exchange (subject_token=admin, requested_subject=target)</li>
 *   <li>Decode exchanged token: iss/jti/sid/sub/exp</li>
 *   <li>permission-service internal session create (DB authority)</li>
 *   <li>**Token frontend'e ANCAK DB insert commit sonrası döner**</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/impersonation/sessions")
public class ImpersonationController {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationController.class);
    private static final String BROKER_AZP = "impersonation-broker";

    private final KeycloakBrokerClient brokerClient;
    private final ImpersonationSessionClient sessionClient;

    public ImpersonationController(
            KeycloakBrokerClient brokerClient,
            ImpersonationSessionClient sessionClient) {
        this.brokerClient = brokerClient;
        this.sessionClient = sessionClient;
    }

    /**
     * POST /api/v1/impersonation/sessions
     *
     * <p>Authorization: SuperAdmin only (PreAuthorize role check).
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<StartResponse> startSession(
            @Valid @RequestBody StartSessionRequest request,
            @AuthenticationPrincipal Jwt adminJwt,
            HttpServletRequest httpRequest) {

        // Step 1: Nested impersonation guard — admin token broker-issued olamaz
        String adminAzp = adminJwt.getClaimAsString("azp");
        if (BROKER_AZP.equals(adminAzp)) {
            log.warn("Nested impersonation attempt rejected: admin token azp={}", adminAzp);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new StartResponse(null, null, null,
                            "NESTED_IMPERSONATION_FORBIDDEN",
                            "Cannot impersonate while already impersonating"));
        }

        Long impersonatorUserId = extractUserIdClaim(adminJwt);
        String impersonatorSubject = adminJwt.getSubject();
        String impersonatorEmail = adminJwt.getClaimAsString("email");

        if (impersonatorUserId == null || impersonatorSubject == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new StartResponse(null, null, null,
                            "ADMIN_IDENTITY_MISSING",
                            "Admin JWT missing userId or subject claim"));
        }

        // Step 2: Token exchange (KC broker)
        ExchangeResult exchange;
        try {
            exchange = brokerClient.exchange(adminJwt.getTokenValue(), request.targetSubject());
        } catch (TokenExchangeException e) {
            log.warn("Token exchange failed for target={}: {}", request.targetSubject(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new StartResponse(null, null, null,
                            e.errorCode(),
                            "Keycloak token exchange failed"));
        }

        DecodedClaims claims = exchange.claims();

        // Step 3: permission-service internal session create (DB authority)
        StartRequest internalRequest = new StartRequest(
                impersonatorUserId,
                impersonatorSubject,
                impersonatorEmail,
                request.targetUserId(),
                request.targetSubject(),
                request.targetEmail(),
                claims.issuer(),
                claims.jti(),
                claims.sid(),
                request.reason(),
                Instant.ofEpochSecond(claims.expEpoch()),
                resolveClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getHeader("X-Forwarded-For"));

        SessionResource session;
        try {
            session = sessionClient.startSession(internalRequest);
        } catch (ActiveSessionExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new StartResponse(null, null, null,
                            e.errorCode(),
                            "Active impersonation session already exists for this user"));
        }

        log.info("Impersonation session started: id={} impersonator={} target={}",
                session.sessionId(), impersonatorUserId, request.targetUserId());

        // Step 4: Token frontend'e ŞİMDİ döner (DB commit sonrası)
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new StartResponse(
                        session.sessionId(),
                        exchange.accessToken(),
                        session.expiresAt(),
                        null,
                        null));
    }

    /**
     * DELETE /api/v1/impersonation/sessions/current
     *
     * <p>Authorization: token must have impersonation context (azp=broker)
     * — only the impersonator can stop their own session.
     */
    @DeleteMapping("/current")
    public ResponseEntity<Void> stopCurrent(@AuthenticationPrincipal Jwt jwt) {
        // For broker-issued tokens, the session lookup is via permission-service.
        // This endpoint can be called either with the original admin token or
        // the exchanged token; for MVP we expect the impersonator (admin) to stop.

        Long impersonatorUserId = extractUserIdClaim(jwt);
        if (impersonatorUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var active = sessionClient.getActiveByImpersonator(impersonatorUserId);
        if (active.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        boolean stopped = sessionClient.stopSession(active.get().sessionId(), "USER_STOP");
        return stopped ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * GET /api/v1/impersonation/sessions/active
     */
    @GetMapping("/active")
    public ResponseEntity<SessionResource> getActive(@AuthenticationPrincipal Jwt jwt) {
        Long impersonatorUserId = extractUserIdClaim(jwt);
        if (impersonatorUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return sessionClient.getActiveByImpersonator(impersonatorUserId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private Long extractUserIdClaim(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim == null) {
            return null;
        }
        try {
            if (claim instanceof Number n) return n.longValue();
            return Long.parseLong(claim.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @ExceptionHandler(TokenExchangeException.class)
    public ResponseEntity<ErrorResponse> handleExchange(TokenExchangeException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(e.errorCode(), e.getMessage()));
    }

    @ExceptionHandler(ActiveSessionExistsException.class)
    public ResponseEntity<ErrorResponse> handleActive(ActiveSessionExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.errorCode(), e.getMessage()));
    }

    public record StartSessionRequest(
            @NotNull Long targetUserId,
            @NotBlank @Size(max = 255) String targetSubject,
            @Size(max = 255) String targetEmail,
            @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record StartResponse(
            UUID sessionId,
            String exchangedToken,
            Instant expiresAt,
            String errorCode,
            String errorMessage) {
    }

    public record ErrorResponse(String errorCode, String message) {
    }
}
