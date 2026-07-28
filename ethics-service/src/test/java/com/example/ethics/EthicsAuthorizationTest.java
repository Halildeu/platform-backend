package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EthicsAuthorizationTest {
    private final OpenFgaAuthzService openFga = mock(OpenFgaAuthzService.class);
    private final OpenFgaProperties properties = enabledProperties();
    private final EthicsAuthorization authorization = new EthicsAuthorization(openFga, properties);
    private final StaffContext staff = new StaffContext(UUID.randomUUID(), "staff-test");

    @Test
    void denyAndUnavailableBothFailClosed() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result(false, "no_relation"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
        verify(openFga).checkNoCacheResult(
                staff.subject(),
                "case_viewer",
                EthicsAuthorization.PRODUCT_OBJECT,
                staff.orgId().toString());

        reset(openFga);
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic outage"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
    }

    @Test
    void conflictAndRecusalOverrideProductAccessWithoutAnExistenceSignal() {
        UUID caseId = UUID.randomUUID();
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(true, "granted"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();

        reset(openFga);
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(true, "granted"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
    }

    @Test
    void partialOutageAfterProductAllowFailsClosed() {
        UUID caseId = UUID.randomUUID();
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "unavailable"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();

        reset(openFga);
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "unavailable"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
    }

    @Test
    void productAccessWithoutConflictOrRecusalAllowsTheCase() {
        UUID caseId = UUID.randomUUID();
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isTrue();
    }

    /**
     * ES-203. The relation is written once; a second call is a no-op rather than a second write.
     *
     * <p>Reaching this twice means two requests raced, not that anyone declared twice — the caller
     * only gets here after {@code require(.., "case_viewer", ..)} passed, and that fails once the
     * relation exists. Measured on the running cell: first POST 204, second POST 404, one ledger
     * entry.
     */
    @Test
    void selfRecusalWritesTheRelationOnceAndIsANoOpOnARace() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));

        authorization.recuseSelf(staff, caseId);
        verify(openFga).writeTuple(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString());

        reset(openFga);
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(true, "granted"));

        authorization.recuseSelf(staff, caseId);
        verify(openFga, never()).writeTuple(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * The recusal is always the caller's own: {@code recuseSelf} takes no subject, so the tuple it
     * writes can only ever name the token's own principal. This pins that property against a future
     * signature change that would let one person recuse another — which would be a way to remove a
     * colleague from a case they are handling.
     */
    @Test
    void selfRecusalCanOnlyEverNameTheCallersOwnPrincipal() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result(false, "no_relation"));

        authorization.recuseSelf(staff, caseId);

        org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(openFga).writeTuple(
                subject.capture(), eq("recused"),
                eq(EthicsAuthorization.CASE_OBJECT), eq(caseId.toString()));
        assertThat(subject.getValue()).isEqualTo(staff.subject());
    }

    /**
     * An unreadable policy engine must not produce a recusal that was never written. Reporting one
     * would put a false statement into an append-only ledger — the one place a wrong entry cannot
     * be taken back.
     */
    @Test
    void selfRecusalRefusesWhenThePolicyEngineDidNotAnswer() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> authorization.recuseSelf(staff, caseId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e -> ((org.springframework.web.server.ResponseStatusException) e).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        verify(openFga, never()).writeTuple(anyString(), anyString(), anyString(), anyString());
    }

    // ---- gateFor: the list-wide form of the same decision -------------------------------
    //
    // Resolving a whole list in three calls is only worth having if it denies exactly what
    // the per-case form denied. The direction that can quietly go wrong is the negative
    // relation: an unreadable "who is recused" list looks identical to "nobody is recused",
    // and reading it the second way hands over the cases recusal exists to withhold.

    @Test
    void gateDeniesEveryCaseWhenTheProductRelationIsAbsent() {
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result(false, "no_relation"));

        var gate = authorization.gateFor(staff, "case_viewer");

        assertThat(gate.allows(UUID.randomUUID())).isFalse();
        // Membership already failed; the blocked lists are not worth asking for.
        verify(openFga, never()).listObjectsResult(anyString(), anyString(), anyString());
    }

    @Test
    void gateDeniesEveryCaseWhenTheConflictListCannotBeRead() {
        grantProduct("case_viewer");
        when(openFga.listObjectsResult(staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT))
                .thenReturn(OpenFgaAuthzService.ObjectListResult.unavailable("circuit_open"));
        // The recusal read must succeed, or an unstubbed null would deny by accident and this
        // test would pass against a gate that ignores availability entirely.
        when(openFga.listObjectsResult(staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT))
                .thenReturn(objects());

        assertThat(authorization.gateFor(staff, "case_viewer").allows(UUID.randomUUID())).isFalse();
    }

    @Test
    void gateDeniesEveryCaseWhenTheRecusalListCannotBeRead() {
        grantProduct("case_viewer");
        when(openFga.listObjectsResult(staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT))
                .thenReturn(objects());
        when(openFga.listObjectsResult(staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT))
                .thenReturn(OpenFgaAuthzService.ObjectListResult.unavailable("transport"));

        assertThat(authorization.gateFor(staff, "case_viewer").allows(UUID.randomUUID())).isFalse();
    }

    @Test
    void gateWithholdsConflictedAndRecusedCasesAndAllowsTheRest() {
        UUID conflicted = UUID.randomUUID();
        UUID recused = UUID.randomUUID();
        UUID ordinary = UUID.randomUUID();
        grantProduct("case_viewer");
        when(openFga.listObjectsResult(staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT))
                .thenReturn(objects(conflicted.toString()));
        when(openFga.listObjectsResult(staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT))
                .thenReturn(objects(recused.toString()));

        var gate = authorization.gateFor(staff, "case_viewer");

        assertThat(gate.allows(conflicted)).isFalse();
        assertThat(gate.allows(recused)).isFalse();
        assertThat(gate.allows(ordinary)).isTrue();
        assertThat(gate.allows(null)).isFalse();
    }

    @Test
    void gateCostsTheSameWhateverTheCaseloadIs() {
        grantProduct("case_viewer");
        when(openFga.listObjectsResult(anyString(), anyString(), anyString())).thenReturn(objects());

        var gate = authorization.gateFor(staff, "case_viewer");
        for (int i = 0; i < 500; i++) {
            gate.allows(UUID.randomUUID());
        }

        // The reason this method exists: the per-case form asked three times per row, so a
        // list of 138 made 414 round trips to answer 6 KB. Deciding 500 cases must still
        // cost one membership check and two list reads, or the fix has been undone.
        verify(openFga, times(1)).checkNoCacheResult(anyString(), anyString(), anyString(), anyString());
        verify(openFga, times(2)).listObjectsResult(anyString(), anyString(), anyString());
    }

    private static OpenFgaAuthzService.ObjectListResult objects(String... ids) {
        return new OpenFgaAuthzService.ObjectListResult(true, java.util.List.of(ids), "ok");
    }

    private void grantProduct(String relation) {
        when(openFga.checkNoCacheResult(
                staff.subject(), relation, EthicsAuthorization.PRODUCT_OBJECT, staff.orgId().toString()))
                .thenReturn(result(true, "granted"));
    }

    private static OpenFgaAuthzService.CheckResult result(boolean allowed, String reason) {
        return new OpenFgaAuthzService.CheckResult(allowed, reason);
    }

    private static OpenFgaProperties enabledProperties() {
        var value = new OpenFgaProperties();
        value.setEnabled(true);
        value.setStoreId("test-store");
        value.setModelId("test-model");
        return value;
    }
}
