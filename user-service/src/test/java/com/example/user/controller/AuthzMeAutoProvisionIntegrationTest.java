package com.example.user.controller;

import com.example.commonauth.AuthenticatedUserLookupService;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.user.UserApplication;
import com.example.user.config.TestSecurityConfig;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FINDING 2 — filter-ordering behaviour test for the Keycloak user
 * lazy-provision bridge.
 *
 * <p>The {@code UserControllerV1Test} / {@code NotificationPreferencesControllerV1Test}
 * auto-provision cases pass even if {@code KeycloakUserAutoProvisionFilter}
 * never runs, because every {@code @RequestMapping} controller resolves the
 * current user through {@code CurrentUserResolver}, which re-applies the
 * gate as a safety net. They therefore do <em>not</em> prove the filter
 * runs in the right place.
 *
 * <p>{@code AuthzProxyControllerV1#getMyAuthorization}
 * ({@code GET /api/v1/authz/me}) is the genuinely filter-dependent
 * consumer: it does <strong>not</strong> call {@code CurrentUserResolver},
 * it reads {@code ScopeContextHolder}, which is populated by
 * {@code ScopeContextFilter}. {@code ScopeContextFilter} resolves the
 * numeric backend user id via {@code AuthenticatedUserLookupService}
 * (email → {@code users.id} SQL lookup). For an M365 first-login that
 * lookup only finds a row if {@code KeycloakUserAutoProvisionFilter}
 * already created it — and the filter is registered (see
 * {@code AutoProvisionConfig}) at order {@code LOWEST_PRECEDENCE - 20},
 * which is earlier than {@code ScopeContextFilter}'s
 * {@code LOWEST_PRECEDENCE - 10}.
 *
 * <p>So: a first-login {@code GET /api/v1/authz/me} that comes back with a
 * <em>numeric</em> {@code userId} (the new {@code users.id}) — and a
 * freshly created {@code users} row — proves the auto-provision filter ran
 * before {@code ScopeContextFilter}. If the ordering were wrong, the row
 * would not exist when {@code ScopeContextFilter} runs and the response
 * {@code userId} would fall back to the JWT subject ({@code kc-sub-*}).
 *
 * <h2>Test wiring</h2>
 * <ul>
 *   <li>{@code erp.openfga.enabled=true} — otherwise {@code ScopeContextFilter}
 *       short-circuits to the dev scope ({@code userId="dev-user"}) and
 *       never calls the user-id lookup, so the assertion would be
 *       meaningless.</li>
 *   <li>A {@code @Primary} mocked {@link OpenFgaAuthzService} whose scope
 *       reads return empty / {@code check}=false — so the OpenFGA fetch
 *       <em>succeeds</em> (no real server) and {@code ScopeContextFilter}
 *       does not fall back to the dev scope. {@code ScopeContext.userId()}
 *       is then exactly the resolved id.</li>
 *   <li>{@code scope.cache.enabled=false} — skips the
 *       {@code RemoteAuthzVersionProvider} HTTP probe, keeping the test
 *       deterministic and fast.</li>
 *   <li>A {@code @Primary} {@link AuthenticatedUserLookupService} test
 *       subclass — the production class probes table existence with
 *       PostgreSQL's {@code to_regclass()}, which H2 does not implement,
 *       so under H2 its email→id lookup is silently skipped and the
 *       numeric-id resolution can never happen. The subclass keeps all
 *       of the production claim-extraction logic and only swaps in a
 *       portable {@code SELECT id FROM users WHERE lower(email)=?}
 *       lookup — i.e. it reproduces exactly what production Postgres
 *       does, so the filter-ordering assertion stays genuine.</li>
 * </ul>
 */
@SpringBootTest(classes = UserApplication.class, webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({TestSecurityConfig.class, AuthzMeAutoProvisionIntegrationTest.OpenFgaStubConfig.class})
@TestPropertySource(properties = {
        "SECURITY_JWT_ISSUER=auth-service",
        "SECURITY_JWT_AUDIENCE=user-service,frontend",
        // OpenFGA ON so ScopeContextFilter actually resolves the numeric
        // user id (instead of short-circuiting to the dev scope).
        "erp.openfga.enabled=true",
        // Cache off → ScopeContextFilter skips the RemoteAuthzVersionProvider
        // HTTP probe; deterministic + no 2s connect timeout.
        "scope.cache.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class AuthzMeAutoProvisionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDb() {
        userRepository.deleteAll();
    }

    /**
     * M365 first-login {@code GET /api/v1/authz/me}: a gate-passing JWT
     * with no pre-existing {@code users} row. The auto-provision filter
     * must create the row before {@code ScopeContextFilter} runs, so the
     * response carries the new numeric {@code users.id} — not the
     * {@code kc-sub-*} subject.
     *
     * <p>Note — {@code /authz/me} is deliberately NOT activation-gated.
     * The user / notification transaction endpoints reject a passive
     * ({@code enabled=false}) account with {@code 403 ACCOUNT_DISABLED}
     * via {@code CurrentUserResolver}; {@code /authz/me} is exempt because
     * it is the identity-bootstrap endpoint — the SPA must be able to call
     * it on first login to discover "you exist but are pending admin
     * activation". A freshly auto-provisioned passive user is
     * {@code role=USER} with no company / modules / scopes / OpenFGA
     * tuples, so this response carries identity only — no effective
     * authority. The numeric-identity assertions below stand; the absence
     * of grants follows from the empty profile.
     */
    @Test
    void authzMe_m365FirstLogin_filterCreatesRowBeforeScopeContext_userIdIsNumeric() throws Exception {
        String email = "authz-me-first@example.com";
        String subject = "kc-sub-authz-me-first";
        assertThat(userRepository.findByEmail(email)).isEmpty();

        String token = issueM365Token(email, subject,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        MvcResult result = mockMvc.perform(get("/api/v1/authz/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        // (a) the request created the backend users row.
        User provisioned = userRepository.findByEmail(email).orElseThrow();
        assertThat(provisioned.getKcSubject()).isEqualTo(subject);
        assertThat(provisioned.getRole()).isEqualTo("USER");

        // (b) the response userId is the NEW numeric users.id — proving
        //     KeycloakUserAutoProvisionFilter ran before ScopeContextFilter
        //     (else the email→id lookup would miss and userId would be the
        //     kc-sub-* subject string).
        String expectedUserId = String.valueOf(provisioned.getId());
        mockMvc.perform(get("/api/v1/authz/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(expectedUserId));

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"userId\":\"" + expectedUserId + "\"");
        assertThat(body).doesNotContain(subject);   // not the kc-sub-* string
        assertThat(body).doesNotContain("dev-user"); // not the dev fallback
    }

    /**
     * Regression for the M365 lazy-provision deadlock (the live bug). The
     * frontend's first-login self-probe hits
     * {@code GET /api/users/by-email/{self}}, which {@code shouldNotFilter}
     * previously EXCLUDED — so the authenticated request never triggered
     * auto-provision, the backend row was never created, the probe 404'd
     * forever and login deadlocked. With the exclusion removed, a gate-passing
     * M365 self-probe must create the row in the SAME request (the provision
     * commits in a {@code REQUIRES_NEW} tx before the controller's lookup).
     */
    @Test
    void byEmailSelfProbe_m365FirstLogin_createsRow() throws Exception {
        String email = "byemail-self@example.com";
        String subject = "kc-sub-byemail-self";
        assertThat(userRepository.findByEmail(email)).isEmpty();

        String token = issueM365Token(email, subject,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        mockMvc.perform(get("/api/users/by-email/{email}", email)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        User provisioned = userRepository.findByEmail(email).orElseThrow();
        assertThat(provisioned.getKcSubject()).isEqualTo(subject);
        assertThat(provisioned.getRole()).isEqualTo("USER");
    }

    /**
     * Security invariant (Codex cross-AI review): on a {@code by-email}
     * request the filter provisions ONLY the token's own identity, never the
     * email in the path variable. An authenticated M365 caller looking up a
     * DIFFERENT user's email gets its OWN row ensured (idempotently); the
     * looked-up "victim" is NOT provisioned — the gate reads the token's
     * {@code sub}/{@code email}, not the path.
     */
    @Test
    void byEmail_provisionsTokenOwnerOnly_notPathEmail() throws Exception {
        String callerEmail = "byemail-caller@example.com";
        String callerSubject = "kc-sub-byemail-caller";
        String victimEmail = "byemail-victim@example.com";
        assertThat(userRepository.findByEmail(callerEmail)).isEmpty();
        assertThat(userRepository.findByEmail(victimEmail)).isEmpty();

        String callerToken = issueM365Token(callerEmail, callerSubject,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // Caller (valid M365) looks up the VICTIM's email.
        mockMvc.perform(get("/api/users/by-email/{email}", victimEmail)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken));

        // The caller's OWN row was ensured; the victim's was NOT created.
        assertThat(userRepository.findByEmail(callerEmail)).isPresent();
        assertThat(userRepository.findByEmail(victimEmail)).isEmpty();
    }

    /**
     * Orphaned-attribute regression (the live owner-account lockout this
     * change fixes, Codex 019eeffd). An M365 first-login whose token ALSO
     * carries a numeric {@code userId} claim pointing at a NON-existent id —
     * a stale Keycloak attribute left over from an earlier provisioning
     * before a dev-DB reset — must STILL auto-provision via {@code sub}/email.
     * Before the fix the numeric {@code userId} was a gate-level deny
     * ({@code has-user-id-claim}) and the {@code findById}-first resolver
     * path 403'd, permanently locking the user out. The created row is the
     * usual passive ({@code enabled=false}) first-login profile awaiting
     * admin activation.
     */
    @Test
    void m365FirstLogin_withStaleUserIdClaim_stillProvisions() throws Exception {
        String email = "stale-userid-first@example.com";
        String subject = "kc-sub-stale-userid-first";
        assertThat(userRepository.findByEmail(email)).isEmpty();
        // A userId that maps to no row — and well past any id this fresh DB
        // would mint — proving resolution is by sub/email, never the claim.
        long orphanUserId = 987654L;
        assertThat(userRepository.findById(orphanUserId)).isEmpty();

        String token = issueM365TokenWithUserId(email, subject,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", orphanUserId);

        // by-email self-probe goes through the auto-provision filter +
        // CurrentUserResolver safety net; for a passive first-login the row
        // is created then the lookup succeeds (200) on the now-existing row.
        mockMvc.perform(get("/api/users/by-email/{email}", email)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // The row now exists for the token's own sub/email and is passive —
        // NOT keyed off the orphaned numeric userId claim.
        User provisioned = userRepository.findByEmail(email).orElseThrow();
        assertThat(provisioned.getKcSubject()).isEqualTo(subject);
        assertThat(provisioned.isEnabled()).isFalse();          // passive — admin must activate
        assertThat(provisioned.getRole()).isEqualTo("USER");
        // The provisioned row got a fresh DB id, never the orphaned claim id.
        assertThat(provisioned.getId()).isNotEqualTo(orphanUserId);
    }

    /**
     * Gate-denied local-token path still honors a numeric {@code userId}
     * claim when the resolved row actually belongs to this identity. A token
     * with NO {@code entra_tid} (so the gate denies {@code missing-entra-tid})
     * whose {@code sub}=email per the local-issuer convention, carrying
     * {@code userId} = an EXISTING active row whose email matches the token,
     * resolves that row (the normal backend-issued-token path is unbroken).
     */
    @Test
    void gateDenied_numericUserIdClaim_matchingEmail_resolvesOwnRow() throws Exception {
        String email = "local-own-row@example.com";
        User seeded = seedActiveUser(email, "kc-sub-local-own-row");

        // Gate-denied (no entra_tid), sub = email (local convention), userId
        // = the user's OWN existing row id.
        String token = issueLocalTokenWithUserId(email, seeded.getId());

        mockMvc.perform(get("/api/v1/users/me/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(seeded.getId().intValue()))
                .andExpect(jsonPath("$.email").value(email));
    }

    /**
     * Anti-cross-user — the core security assertion (Codex 019eeffd). Seed
     * user A and a DIFFERENT user B. A gate-denied token for identity A
     * ({@code sub}/email = A) that carries {@code userId} = B's id must NOT
     * resolve B; the resolver ignores the mismatched claim
     * ({@code stale-user-id-claim-mismatch}) and resolves A's OWN row by
     * email. A stale/reused id can never let one identity transact as
     * another.
     */
    @Test
    void gateDenied_staleUserIdClaim_mapsToDifferentUser_doesNotCrossResolve() throws Exception {
        String emailA = "cross-a@example.com";
        String emailB = "cross-b@example.com";
        User userA = seedActiveUser(emailA, "kc-sub-cross-a");
        User userB = seedActiveUser(emailB, "kc-sub-cross-b");
        assertThat(userA.getId()).isNotEqualTo(userB.getId());

        // Token is identity A (sub/email = A) but carries B's id as userId.
        String token = issueLocalTokenWithUserId(emailA, userB.getId());

        mockMvc.perform(get("/api/v1/users/me/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                // Resolves A's OWN row by email — never B's.
                .andExpect(jsonPath("$.id").value(userA.getId().intValue()))
                .andExpect(jsonPath("$.email").value(emailA))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(userB.getId().intValue())))
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.not(emailB)));
    }

    /** Seeds an ENABLED, active backend user row with the given email + kc subject. */
    private User seedActiveUser(String email, String kcSubject) {
        User user = new User();
        user.setEmail(email);
        user.setName(email);
        user.setPassword("x");
        user.setEnabled(true);
        user.setRole("ADMIN");
        user.setKcSubject(kcSubject);
        return userRepository.save(user);
    }

    /**
     * Issues an RS256 token simulating an M365 first-login: allow-listed
     * issuer ({@code platform-test}), the {@code entra_tid} marker claim,
     * {@code email_verified=true}, an explicit {@code sub} — and crucially
     * NO numeric {@code userId} claim, so this is a brand-new identity
     * with no established backend profile.
     */
    private String issueM365Token(String email, String subject, String entraTid) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer("platform-test")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("entra_tid", entraTid)
                .claim("email_verified", Boolean.TRUE)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Same gate-PASSING M365 token as {@link #issueM365Token}, but ALSO
     * carrying a numeric {@code userId} claim — the orphaned-attribute case.
     * After the fix the claim is not a gate condition, so the token is still
     * allowed and provisions via {@code sub}/email.
     */
    private String issueM365TokenWithUserId(String email, String subject, String entraTid, long userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer("platform-test")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("entra_tid", entraTid)
                .claim("email_verified", Boolean.TRUE)
                .claim("userId", userId)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Issues a gate-DENIED local/backend token: allow-listed issuer but NO
     * {@code entra_tid} marker (so the gate denies {@code missing-entra-tid}
     * while {@code allow-local-keycloak} stays false), {@code sub}=email per
     * the local-issuer convention, plus a numeric {@code userId} claim —
     * exercising {@code CurrentUserResolver}'s gate-denied numeric-userId path.
     */
    private String issueLocalTokenWithUserId(String email, long userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(email) // local-issuer convention: sub carries the email
                .issuer("platform-test")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("userId", userId)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Test wiring for {@code GET /api/v1/authz/me} filter-ordering proof.
     */
    @TestConfiguration
    static class OpenFgaStubConfig {

        /**
         * Replaces the real OpenFGA-backed {@link OpenFgaAuthzService}
         * with a mock that returns empty scopes / {@code check}=false.
         * This lets {@code ScopeContextFilter}'s OpenFGA fetch
         * <em>succeed</em> (there is no OpenFGA server in the test) so it
         * does not fall back to the dev scope — {@code ScopeContext.userId()}
         * then reflects exactly the id resolved by
         * {@link AuthenticatedUserLookupService}.
         */
        @Bean
        @Primary
        OpenFgaAuthzService stubOpenFgaAuthzService() {
            OpenFgaAuthzService svc = mock(OpenFgaAuthzService.class);
            when(svc.listObjectIds(anyString(), anyString(), anyString())).thenReturn(Set.of());
            when(svc.check(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
            return svc;
        }

        /**
         * H2-portable {@link AuthenticatedUserLookupService}. The
         * production class checks table existence with PostgreSQL's
         * {@code to_regclass()} before its email→id {@code SELECT};
         * H2 has no {@code to_regclass}, so under H2 the check fails,
         * the SQL is skipped, and {@code resolve()} can never return a
         * numeric id (it falls back to the JWT subject) — which would
         * make the filter-ordering assertion meaningless.
         *
         * <p>This subclass keeps every bit of the production
         * claim-extraction logic via {@code super.resolve(jwt)} and only
         * fills in the numeric id with a portable
         * {@code SELECT id FROM users WHERE lower(email)=?} — exactly
         * the lookup production Postgres performs. The filter-ordering
         * proof therefore remains genuine: the id resolves only because
         * {@code KeycloakUserAutoProvisionFilter} created the row before
         * {@code ScopeContextFilter} ran.
         */
        @Bean
        @Primary
        AuthenticatedUserLookupService h2PortableUserLookupService(JdbcTemplate jdbcTemplate) {
            return new AuthenticatedUserLookupService(jdbcTemplate, "users") {
                @Override
                public ResolvedAuthenticatedUser resolve(Jwt jwt) {
                    ResolvedAuthenticatedUser base = super.resolve(jwt);
                    if (base.numericUserId() != null || base.email() == null) {
                        return base;
                    }
                    Long id = jdbcTemplate.query(
                            "select id from users where lower(email) = ? limit 1",
                            rs -> rs.next() ? rs.getLong("id") : null,
                            base.email().toLowerCase(Locale.ROOT));
                    if (id == null) {
                        return base;
                    }
                    return new ResolvedAuthenticatedUser(id, Long.toString(id), base.email());
                }
            };
        }
    }
}
