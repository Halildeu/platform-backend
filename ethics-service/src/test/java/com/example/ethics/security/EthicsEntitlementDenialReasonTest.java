package com.example.ethics.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ethics.security.EthicsEntitlementVerifier.AuthzMeResponse;
import com.example.ethics.security.EthicsEntitlementVerifier.Denial;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 — a refused handler must leave a named reason behind (#970).
 *
 * <p>Authorizing one real person for this product takes six steps across three systems:
 * a role assignment row, a permission-store tuple, two ethics-store tuples, an aligned
 * org attribute, and sometimes a data move. Miss one and the symptom is a 403 that names
 * nothing — every failure, including the dependency being down, collapsed into the same
 * silent {@code false} with no log line. Reconstructing which of the six was missing cost
 * hours.
 *
 * <p>The caller's answer does not change here and must not: the response is identical for
 * every reason, so dependency health stays undisclosed. What these tests hold is that the
 * <em>server</em> can tell them apart.
 */
class EthicsEntitlementDenialReasonTest {

    private static AuthzMeResponse entitled() {
        return new AuthzMeResponse("7", 7L, false, List.of("ETIK_SPEAK_MANAGER"),
                Map.of("ETHIC", "MANAGE"), List.of("ETHIC"), List.of("ETHIC"));
    }

    @Test
    @DisplayName("tam yetkili yanıt reddedilmez")
    void afullyEntitledResponseIsNotDenied() {
        assertThat(EthicsEntitlementVerifier.denialReason(entitled())).isNull();
        assertThat(EthicsEntitlementVerifier.isExactEthicManage(entitled())).isTrue();
    }

    /**
     * The reason this issue exists: the most common real gap is a missing role assignment,
     * and it used to be indistinguishable from an outage.
     */
    @Test
    @DisplayName("eksik rol, adıyla raporlanır")
    void aMissingRoleIsNamed() {
        var noRole = new AuthzMeResponse("7", 7L, false, List.of(),
                Map.of("ETHIC", "MANAGE"), List.of("ETHIC"), List.of("ETHIC"));
        assertThat(EthicsEntitlementVerifier.denialReason(noRole)).isEqualTo(Denial.ROLE_MISSING);
    }

    @Test
    @DisplayName("her eksik halka kendi adıyla ayrılır")
    void eachMissingLinkHasItsOwnName() {
        record Case(String label, AuthzMeResponse response, Denial expected) {}
        var cases = List.of(
                new Case("yanıt yok", null, Denial.EMPTY_RESPONSE),
                new Case("kimlik eksik",
                        new AuthzMeResponse(null, 7L, false, List.of(), Map.of(), List.of(), List.of()),
                        Denial.IDENTITY_INCOMPLETE),
                new Case("kimlik uyuşmuyor",
                        new AuthzMeResponse("9", 7L, false, List.of(), Map.of(), List.of(), List.of()),
                        Denial.IDENTITY_MISMATCH),
                new Case("super admin",
                        new AuthzMeResponse("7", 7L, true, List.of(), Map.of(), List.of(), List.of()),
                        Denial.SUPER_ADMIN_NOT_ALLOWED),
                new Case("projeksiyon eksik",
                        new AuthzMeResponse("7", 7L, false, null, null, null, null),
                        Denial.PROJECTION_INCOMPLETE),
                new Case("modül MANAGE değil",
                        new AuthzMeResponse("7", 7L, false, List.of("ETIK_SPEAK_MANAGER"),
                                Map.of("ETHIC", "READ"), List.of("ETHIC"), List.of("ETHIC")),
                        Denial.MODULE_NOT_MANAGE),
                new Case("modül izinli değil",
                        new AuthzMeResponse("7", 7L, false, List.of("ETIK_SPEAK_MANAGER"),
                                Map.of("ETHIC", "MANAGE"), List.of(), List.of("ETHIC")),
                        Denial.MODULE_NOT_ALLOWED),
                new Case("izin yok",
                        new AuthzMeResponse("7", 7L, false, List.of("ETIK_SPEAK_MANAGER"),
                                Map.of("ETHIC", "MANAGE"), List.of("ETHIC"), List.of()),
                        Denial.PERMISSION_MISSING));

        for (Case c : cases) {
            assertThat(EthicsEntitlementVerifier.denialReason(c.response()))
                    .as("%s", c.label()).isEqualTo(c.expected());
        }
    }

    /**
     * Every reason must still deny. A named reason that accidentally allowed would be far
     * worse than the silence it replaced.
     */
    @Test
    @DisplayName("adı konan her sebep yine de reddeder")
    void namingAReasonNeverGrantsAccess() {
        var responses = List.of(
                new AuthzMeResponse("7", 7L, false, List.of(), Map.of("ETHIC", "MANAGE"), List.of("ETHIC"), List.of("ETHIC")),
                new AuthzMeResponse("9", 7L, false, List.of("ETIK_SPEAK_MANAGER"), Map.of("ETHIC", "MANAGE"), List.of("ETHIC"), List.of("ETHIC")),
                new AuthzMeResponse("7", 7L, true, List.of("ETIK_SPEAK_MANAGER"), Map.of("ETHIC", "MANAGE"), List.of("ETHIC"), List.of("ETHIC")));
        for (AuthzMeResponse response : responses) {
            assertThat(EthicsEntitlementVerifier.denialReason(response)).isNotNull();
            assertThat(EthicsEntitlementVerifier.isExactEthicManage(response)).isFalse();
        }
        assertThat(EthicsEntitlementVerifier.isExactEthicManage(null)).isFalse();
    }

    /**
     * The boolean the service reads and the reason the operator reads have to be the same
     * rule. Two copies would drift, and the drifting one would be the unwatched one.
     */
    @Test
    @DisplayName("boolean ile sebep aynı kuraldan geliyor")
    void theBooleanAndTheReasonCannotDisagree() {
        var samples = List.of(
                entitled(),
                new AuthzMeResponse("7", 7L, false, List.of(), Map.of("ETHIC", "MANAGE"), List.of("ETHIC"), List.of("ETHIC")),
                new AuthzMeResponse("7", 7L, false, List.of("ETIK_SPEAK_MANAGER"), Map.of(), List.of("ETHIC"), List.of("ETHIC")));
        for (AuthzMeResponse response : samples) {
            assertThat(EthicsEntitlementVerifier.isExactEthicManage(response))
                    .isEqualTo(EthicsEntitlementVerifier.denialReason(response) == null);
        }
    }
}
