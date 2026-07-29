package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Faz 35 ES-403 — the organisation can read what it holds (#885).
 *
 * <p>The endpoint is read-only on purpose, and that is the part worth guarding: a subscription
 * is a commercial fact granted by the vendor, so a write here would let an organisation grant
 * itself {@code SUBJECT_REVEAL} — the capability the catalog refuses to bundle precisely
 * because its misuse cannot be undone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EntitlementsEndpointTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000004a3");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-0000000004b7");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.example.ethics.catalog.EthicsEntitlements entitlements;

    @MockitoBean com.example.ethics.security.EthicsEntitlementVerifier manageAuthority;

    @BeforeEach
    void staffMayAsk() {
        when(manageAuthority.hasManageEntitlement(anyString())).thenReturn(true);
        jdbc.update("delete from ethics_org_subscription");
        // The answer is cached for a TTL, so deleting the rows is not enough — which is the
        // production behaviour, not a test artefact: a grant made out of band becomes visible
        // within one TTL. Each test starts from a known cache rather than inheriting the
        // previous one's.
        entitlements.invalidate(ORG);
        entitlements.invalidate(OTHER_ORG);
    }

    private void grant(UUID orgId, String productId) {
        jdbc.update("""
            insert into ethics_org_subscription (id, org_id, product_id, active, granted_at)
            values (?, ?, ?, true, now())
            """, UUID.randomUUID(), orgId, productId);
    }

    private static RequestPostProcessor staffOf(UUID orgId) {
        return jwt().jwt(j -> j.subject("staff-" + orgId).claim("org_id", orgId.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_ethics:case:manage"));
    }

    @Test
    @DisplayName("kurum satın aldığı ürünleri ve yetenekleri görür")
    void anOrganisationSeesWhatItBought() throws Exception {
        grant(ORG, "etik-speak-core");

        mvc.perform(get("/api/v1/ethics/entitlements").with(staffOf(ORG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0]").value("etik-speak-core"))
                .andExpect(jsonPath("$.capabilities").value(
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "EVIDENCE_ATTACHMENTS", "SLA_NOTIFICATIONS")))
                .andExpect(jsonPath("$.resolution").value("HELD"));
    }

    /**
     * The organisation comes from the token, never from the request. An id in a parameter
     * would be a way to ask about someone else's commercial state, so the property is asserted
     * as an outcome: a second organisation's grant must not appear in this answer.
     */
    @Test
    @DisplayName("başka kurumun aboneliği bu cevaba sızmaz")
    void anotherOrganisationsSubscriptionNeverAppears() throws Exception {
        grant(OTHER_ORG, "etik-speak-plus");

        mvc.perform(get("/api/v1/ethics/entitlements").with(staffOf(ORG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty())
                .andExpect(jsonPath("$.capabilities").isEmpty())
                .andExpect(jsonPath("$.resolution").value("HELD"));
    }

    /** Holding nothing is a real answer, and it is reported as one rather than as an error. */
    @Test
    @DisplayName("hiçbir ürün yoksa boş ama kesin cevap döner")
    void holdingNothingIsAnAnswerNotAFailure() throws Exception {
        mvc.perform(get("/api/v1/ethics/entitlements").with(staffOf(ORG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isEmpty())
                .andExpect(jsonPath("$.resolution").value("HELD"));
    }

    /** Commercial state changes the moment a grant is made; no shared cache may outlive that. */
    @Test
    @DisplayName("cevap önbelleğe alınmaz")
    void theAnswerIsNotCachedDownstream() throws Exception {
        mvc.perform(get("/api/v1/ethics/entitlements").with(staffOf(ORG)))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    /** Without the manage authority the question is not answered at all. */
    @Test
    @DisplayName("ETHIC=MANAGE yetkisi olmayan personel cevabı alamaz")
    void staffWithoutManageAuthorityIsRefused() throws Exception {
        when(manageAuthority.hasManageEntitlement(anyString())).thenReturn(false);

        mvc.perform(get("/api/v1/ethics/entitlements").with(staffOf(ORG)))
                .andExpect(status().isForbidden());
    }

    /**
     * <strong>Read-only, asserted against the source.</strong> The reason no write exists is a
     * decision, not an omission, and a runtime test would pass right up until someone added
     * the method — then fail somewhere far from the line they wrote.
     */
    @Test
    @DisplayName("uçta yazma metodu yok")
    void theEndpointExposesNoWriteMethod() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/example/ethics/api/EntitlementsController.java"))
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");

        assertThat(source)
                .as("abonelik yazma ucu eklenmiş — kurum kendine SUBJECT_REVEAL verebilir")
                .doesNotContain("@PostMapping")
                .doesNotContain("@PutMapping")
                .doesNotContain("@PatchMapping")
                .doesNotContain("@DeleteMapping");
    }
}
