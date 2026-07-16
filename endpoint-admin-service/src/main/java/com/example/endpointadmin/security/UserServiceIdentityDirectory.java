package com.example.endpointadmin.security;

import com.example.commonauth.identity.ResolvedUserIdentity;
import com.example.commonauth.identity.UserIdentityDirectory;
import com.example.commonauth.identity.UserIdentityDirectoryUnavailableException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * endpoint-admin's adapter to the canonical identity authority (board #2532, umbrella #2530).
 *
 * <p>Calls user-service's narrow surface
 * {@code POST /api/users/internal/authenticated-principal/resolve} — deliberately NOT
 * {@code /internal/by-email/{email}} (that one returns the password hash and the role catalogue,
 * which an authorization decision has no business receiving).
 *
 * <p><b>Failure semantics are the point of this class.</b> The pre-existing lookup in
 * permission-service swallows transport failures and returns {@code null} even on 5xx, which a
 * caller cannot distinguish from "no such user" — an outage silently becomes a deny. Here:
 * <ul>
 *   <li>{@code 404} ⇒ {@link Optional#empty()} — a real "no canonical user" answer.</li>
 *   <li>Anything else (5xx, 401/403 misconfiguration, timeout, connection refused) ⇒
 *       {@link UserIdentityDirectoryUnavailableException}, which the resolver maps to
 *       {@code 503 IDENTITY_DIRECTORY_UNAVAILABLE}.</li>
 * </ul>
 * The reason this matters: the alternative is falling back to the token's raw {@code sub} or
 * {@code userId} claim, and that is exactly the defect this slice removes — a Keycloak UUID used as
 * an OpenFGA subject checks tuples that were written for the numeric id, so it can only answer
 * "no", and an authorized admin gets 403 with no error logged anywhere.
 */
public class UserServiceIdentityDirectory implements UserIdentityDirectory {

    private static final Logger log = LoggerFactory.getLogger(UserServiceIdentityDirectory.class);
    private static final String PATH = "/api/users/internal/authenticated-principal/resolve";

    /** Wire shape of user-service's response — kept minimal on purpose (no credentials). */
    record ResolveResponse(
            Long userId,
            Boolean subjectMatched,
            String email,
            Boolean enabled,
            Boolean deleted,
            Long companyId) {}

    private record ResolveRequest(String issuer, String subject, String email) {}

    private final RestClient client;

    public UserServiceIdentityDirectory(RestClient client) {
        this.client = client;
    }

    @Override
    public Optional<ResolvedUserIdentity> resolve(String issuer, String subject, String email) {
        if (client == null) {
            // Misconfiguration is an outage, not a deny: without the directory we cannot know who
            // this principal is, and guessing from claims is the bug we are removing.
            throw new UserIdentityDirectoryUnavailableException(
                    "user-service identity client is not configured");
        }
        try {
            ResolveResponse body = client.post()
                    .uri(PATH)
                    .body(new ResolveRequest(issuer, subject, email))
                    .retrieve()
                    .body(ResolveResponse.class);

            if (body == null || body.userId() == null) {
                // 2xx with an unusable body is not "no such user" — it is an unreadable answer.
                throw new UserIdentityDirectoryUnavailableException(
                        "user-service returned an empty/unusable identity payload");
            }
            return Optional.of(new ResolvedUserIdentity(
                    body.userId(),
                    subject,
                    body.email(),
                    Boolean.TRUE.equals(body.subjectMatched()),
                    Boolean.TRUE.equals(body.enabled()),
                    Boolean.TRUE.equals(body.deleted()),
                    body.companyId()));

        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();   // the only legitimate "no canonical user" signal
            }
            // 401/403 here means OUR service token is wrong — that is our outage, not the user's
            // problem, and must never degrade into "user has no account".
            throw new UserIdentityDirectoryUnavailableException(
                    "user-service identity resolve failed with HTTP " + ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            log.warn("identity directory transport failure: {}", ex.getMessage());
            throw new UserIdentityDirectoryUnavailableException(
                    "user-service identity resolve transport failure", ex);
        }
    }
}
