package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.ScopeObjectIdCodec;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Board #2542 — {@link TupleSyncService#syncScopeTuples} must emit the ADR-0008 <em>canonical</em>
 * object id, closing the writer half of the #2530 principal/object contract drift.
 *
 * <p><b>What was wrong.</b> permission-service had two scope-tuple writers on two encodings:
 * <pre>
 *   DataAccessScopeTupleEncoder  (POST /api/v1/access/scope)  project:wc-project-1204   canonical
 *   TupleSyncService             (role/scope bulk assign)     project:1204              legacy
 * </pre>
 * #2531 fixed the <em>reader</em> ({@code ScopeObjectIdCodec} accepts both, flagging the legacy
 * form) so live access was not revoked, and left the writer migration to this issue.
 *
 * <p><b>What this test pins.</b> Every emitted scope tuple carries exactly
 * {@code ScopeObjectIdCodec.encode(objectType, id)}. That is the same function the canonical
 * encoder's round-trip test asserts against ({@code DataAccessScopeTupleRoundTripTest}), so the
 * two writers can no longer drift apart silently: a revert to {@code String.valueOf(id)} fails
 * here, and a change to the canonical prefixes fails there.
 *
 * <p><b>Why re-decoding is part of the assertion.</b> Asserting the literal string alone would
 * still pass for an id the reader cannot decode. Feeding each emitted id back through
 * {@code decode} and requiring {@code legacyNumeric() == false} pins the property that actually
 * matters at runtime: the reader resolves it, and resolves it via the canonical branch.
 */
@ExtendWith(MockitoExtension.class)
class TupleSyncServiceCanonicalObjectIdTest {

    @Mock OpenFgaAuthzService authzService;
    @Mock RolePermissionRepository rolePermissionRepository;
    @Mock UserRoleAssignmentRepository assignmentRepository;
    @Mock AuthzVersionService authzVersionService;

    TupleSyncService service;

    @BeforeEach
    void setUp() {
        service = new TupleSyncService(
                authzService,
                rolePermissionRepository,
                assignmentRepository,
                authzVersionService,
                null);
    }

    @SuppressWarnings("unchecked")
    private List<ClientTupleKey> captureAllTuples(int expectedBatches) {
        ArgumentCaptor<List<ClientTupleKey>> captor = ArgumentCaptor.forClass(List.class);
        verify(authzService, times(expectedBatches)).writeTuples(captor.capture());
        List<ClientTupleKey> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    @Test
    @DisplayName("#2542: all four scope types emit the ADR-0008 canonical slug, not a bare numeric")
    void allScopeTypesEmitCanonicalObjectId() {
        service.syncScopeTuples("1204",
                List.of(1L),      // company  → company:wc-our-company-1
                List.of(1204L),   // project  → project:wc-project-1204
                List.of(3792L),   // warehouse→ warehouse:wc-department-3792
                List.of(7L),      // branch   → branch:wc-branch-7
                true);

        List<String> objects = captureAllTuples(4).stream().map(ClientTupleKey::getObject).toList();

        assertThat(objects)
                .as("ADR-0008 § Object id encoding — exact canonical forms")
                .containsExactlyInAnyOrder(
                        "company:wc-our-company-1",
                        "project:wc-project-1204",
                        "warehouse:wc-department-3792",
                        "branch:wc-branch-7");
    }

    @Test
    @DisplayName("#2542: emitted ids equal ScopeObjectIdCodec.encode — writer/reader single source")
    void emittedIdsMatchCodecEncode() {
        service.syncScopeTuples("1204",
                List.of(1L), List.of(1204L), List.of(3792L), List.of(7L), true);

        for (ClientTupleKey tuple : captureAllTuples(4)) {
            String[] parts = tuple.getObject().split(":", 2);
            String objectType = parts[0];
            String objectId = parts[1];

            Optional<ScopeObjectIdCodec.DecodedObjectId> decoded =
                    ScopeObjectIdCodec.decode(objectType, objectId);

            assertThat(decoded)
                    .as("reader must decode the emitted id for type=%s", objectType)
                    .isPresent();
            assertThat(decoded.get().legacyNumeric())
                    .as("emitted id for type=%s must decode via the CANONICAL branch, not legacy",
                            objectType)
                    .isFalse();
            assertThat(objectId)
                    .as("emitted id must be byte-identical to the shared codec's encode()")
                    .isEqualTo(ScopeObjectIdCodec.encode(objectType, decoded.get().id()));
        }
    }

    @Test
    @DisplayName("#2542 regression: a bare numeric id is never emitted again")
    void bareNumericIsNeverEmitted() {
        service.syncScopeTuples("1204",
                List.of(1L), List.of(1204L), List.of(3792L), List.of(7L), true);

        for (ClientTupleKey tuple : captureAllTuples(4)) {
            String objectId = tuple.getObject().split(":", 2)[1];
            assertThat(objectId)
                    .as("pre-#2542 form was the bare number — it must not reappear")
                    .isNotEqualTo("1")
                    .isNotEqualTo("1204")
                    .isNotEqualTo("3792")
                    .isNotEqualTo("7")
                    .startsWith("wc-");
        }
    }
}
