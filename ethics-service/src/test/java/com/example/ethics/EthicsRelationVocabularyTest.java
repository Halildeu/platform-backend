package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Every relation name this service sends to OpenFGA must be one the model defines.
 *
 * <p>A typo here is silent. {@code can()} fails closed, so asking for {@code case_veiwer} does not
 * raise — it simply denies, and the staff list answers HTTP 200 with an empty array. That is
 * indistinguishable from "this org has no cases", and it is exactly how a missing grant hid behind
 * a healthy-looking system earlier: 21 cases present, list empty, nothing in any log.
 *
 * <p>The model itself lives in platform-k8s-gitops
 * ({@code runtime-artifacts/faz35-etik-speak/authorization-model-v1.fga}) and is covered there by
 * its own completeness gate. This test pins the other end of that coupling: the vocabulary the
 * code actually speaks.
 */
class EthicsRelationVocabularyTest {

    /**
     * Mirrors the relation registry in platform-k8s-gitops
     * ({@code docs/contracts/faz35-authorization-relation-registry.v1.json}). Anything this service
     * asks for that is not here is either a typo or a relation nobody has written a boundary for.
     */
    private static final Set<String> MODEL_RELATIONS = Set.of(
            "viewer", "triager", "handler", "technical_admin", "evidence_approver",
            "ethics_product_admin", "content_denied",
            "case_viewer", "case_triager", "case_handler", "evidence_reveal_approved",
            "product", "conflicted", "recused", "denied", "member");

    private final OpenFgaAuthzService openFga = mock(OpenFgaAuthzService.class);
    private final EthicsAuthorization authorization = new EthicsAuthorization(openFga, enabledProperties());
    private final StaffContext staff = new StaffContext(UUID.randomUUID(), UUID.randomUUID().toString());

    @Test
    void everyRelationTheServiceAsksForExistsInTheModel() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OpenFgaAuthzService.CheckResult(true, "granted"));

        // Exercise every path that names a relation.
        authorization.can(staff, "case_viewer", caseId);
        authorization.can(staff, "case_triager", caseId);
        authorization.can(staff, "case_handler", caseId);
        authorization.isProductMember(staff.subject(), staff.orgId());

        ArgumentCaptor<String> relations = ArgumentCaptor.forClass(String.class);
        verify(openFga, atLeastOnce())
                .checkNoCacheResult(anyString(), relations.capture(), anyString(), anyString());

        List<String> asked = relations.getAllValues();
        assertThat(asked).isNotEmpty();
        assertThat(MODEL_RELATIONS)
                .as("relations this service sent that the model does not define: %s",
                        asked.stream().filter(r -> !MODEL_RELATIONS.contains(r)).toList())
                .containsAll(asked);
    }

    @Test
    void theObjectTypesAreTheOnesTheModelDefines() {
        assertThat(EthicsAuthorization.PRODUCT_OBJECT).isEqualTo("ethics_product");
        assertThat(EthicsAuthorization.CASE_OBJECT).isEqualTo("ethics_case");
    }

    /**
     * The negative relations are asked for by literal name inside {@code can()}, where a typo would
     * turn "is this person conflicted?" into a question about a relation nobody holds — which
     * answers "no" and lets a conflicted party through. Unlike the positive relations, that failure
     * opens access rather than closing it.
     */
    @Test
    void theSubtractiveRelationsAreAskedForByTheirExactNames() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OpenFgaAuthzService.CheckResult(false, "no_relation"));
        when(openFga.checkNoCacheResult(
                staff.subject(), "case_viewer", EthicsAuthorization.PRODUCT_OBJECT, staff.orgId().toString()))
                .thenReturn(new OpenFgaAuthzService.CheckResult(true, "granted"));

        authorization.can(staff, "case_viewer", caseId);

        verify(openFga).checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString());
        verify(openFga).checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString());
    }

    private static OpenFgaProperties enabledProperties() {
        var value = new OpenFgaProperties();
        value.setEnabled(true);
        value.setStoreId("test-store");
        value.setModelId("test-model");
        return value;
    }
}
