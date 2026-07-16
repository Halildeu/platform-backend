package com.example.endpointadmin.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.commonauth.identity.ResolvedUserIdentity;
import com.example.commonauth.identity.UserIdentityDirectoryUnavailableException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * board #2532 — the adapter's failure semantics ARE the fix.
 *
 * <p>The pre-existing lookup in permission-service returns {@code null} even on 5xx, so a caller
 * cannot tell "no such user" from "authority is down" — an outage silently becomes a deny, and the
 * tempting next step is to fall back to the token's raw {@code sub}. That fallback is the defect:
 * a Keycloak UUID used as an OpenFGA subject checks tuples written for the numeric id, always
 * answers "no", and an authorized admin gets 403.
 */
class UserServiceIdentityDirectoryTest {

    private static final String SUB = "2fd0e4f7-c9da-4622-b4b6-b90adab28dd4";
    private static final String EMAIL = "admin@example.com";
    private static final String URL = "http://user-service:8089/api/users/internal/authenticated-principal/resolve";

    private record Fixture(UserServiceIdentityDirectory directory, MockRestServiceServer server) {}

    private static Fixture fixture() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        RestClient client = RestClient.builder(template).baseUrl("http://user-service:8089").build();
        return new Fixture(new UserServiceIdentityDirectory(client), server);
    }

    @Test
    @DisplayName("200 → canonical numeric identity (never the KC UUID)")
    void resolvesNumericIdentity() {
        var f = fixture();
        f.server().expect(MockRestRequestMatchers.requestTo(URL))
                .andExpect(MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"userId":1204,"subjectMatched":true,"email":"admin@example.com",
                         "enabled":true,"deleted":false,"companyId":1}
                        """, MediaType.APPLICATION_JSON));

        Optional<ResolvedUserIdentity> r = f.directory().resolve("iss", SUB, EMAIL);

        assertTrue(r.isPresent());
        assertEquals(1204L, r.get().userId(), "the OpenFGA subject must be the numeric id");
        assertTrue(r.get().usable());
        f.server().verify();
    }

    @Test
    @DisplayName("404 → empty (the ONLY legitimate 'no canonical user' signal)")
    void notFoundIsEmpty() {
        var f = fixture();
        f.server().expect(MockRestRequestMatchers.requestTo(URL))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));

        assertTrue(f.directory().resolve("iss", SUB, EMAIL).isEmpty());
    }

    @Test
    @DisplayName("5xx → UNAVAILABLE, not empty (an outage must not read as 'user has no account')")
    void serverErrorIsUnavailable() {
        var f = fixture();
        f.server().expect(MockRestRequestMatchers.requestTo(URL))
                .andRespond(MockRestResponseCreators.withServerError());

        assertThrows(UserIdentityDirectoryUnavailableException.class,
                () -> f.directory().resolve("iss", SUB, EMAIL));
    }

    @Test
    @DisplayName("401/403 → UNAVAILABLE: OUR service token is broken, not the user's account")
    void authErrorIsOurOutage() {
        for (HttpStatus s : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN}) {
            var f = fixture();
            f.server().expect(MockRestRequestMatchers.requestTo(URL))
                    .andRespond(MockRestResponseCreators.withStatus(s));

            assertThrows(UserIdentityDirectoryUnavailableException.class,
                    () -> f.directory().resolve("iss", SUB, EMAIL),
                    s + " must not degrade into a deny");
        }
    }

    @Test
    @DisplayName("2xx with unusable body → UNAVAILABLE (an unreadable answer is not an answer)")
    void emptyBodyIsUnavailable() {
        var f = fixture();
        f.server().expect(MockRestRequestMatchers.requestTo(URL))
                .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(UserIdentityDirectoryUnavailableException.class,
                () -> f.directory().resolve("iss", SUB, EMAIL));
    }

    @Test
    @DisplayName("disabled/deleted state is carried through, not swallowed")
    void carriesState() {
        var f = fixture();
        f.server().expect(MockRestRequestMatchers.requestTo(URL))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"userId":1204,"subjectMatched":true,"email":"admin@example.com",
                         "enabled":false,"deleted":false,"companyId":null}
                        """, MediaType.APPLICATION_JSON));

        ResolvedUserIdentity id = f.directory().resolve("iss", SUB, EMAIL).orElseThrow();

        assertFalse(id.enabled());
        assertFalse(id.usable(), "the activation gate needs this to be truthful");
    }

    @Test
    @DisplayName("unconfigured client → UNAVAILABLE (misconfiguration is an outage, not a deny)")
    void unconfiguredClientIsUnavailable() {
        var directory = new UserServiceIdentityDirectory(null);
        assertThrows(UserIdentityDirectoryUnavailableException.class,
                () -> directory.resolve("iss", SUB, EMAIL));
    }
}
