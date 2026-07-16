package com.example.permission.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.commonauth.openfga.ScopeObjectIdCodec;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * board #2531 — writer↔reader round-trip contract for canonical data-access scope grants.
 *
 * <p><b>The bug this pins down:</b> {@code POST /api/v1/access/scope} persists a
 * {@link DataAccessScope} and {@link DataAccessScopeTupleEncoder} turns it into an ADR-0008
 * canonical tuple ({@code project:wc-project-1204}). The read side
 * ({@code OpenFgaAuthzService.listObjectIds} → {@code /authz/me.allowedScopes}) used a bare
 * {@code Long.parseLong} and silently dropped every canonical id. Net effect for a real customer:
 * the admin grants access, the API answers {@code 201 Created}, the tuple is in OpenFGA — and the
 * user still gets {@code 403}, with no error logged anywhere.
 *
 * <p>This test locks the two halves together <em>without</em> mocks or a live OpenFGA: whatever the
 * writer encodes, the reader's codec must decode back to the same entity id. If someone later
 * changes one side (e.g. flips the writer to bare numerics, or renames a prefix), this fails
 * instead of silently making grants invisible again.
 */
class DataAccessScopeTupleRoundTripTest {

    private static DataAccessScope scope(DataAccessScope.ScopeKind kind, String refId, UUID userId) {
        DataAccessScope s = new DataAccessScope();
        s.setUserId(userId);
        s.setScopeKind(kind);
        s.setScopeRef("[\"" + refId + "\"]");
        return s;
    }

    @ParameterizedTest(name = "{0} ref={1} → reader decodes back to {1}")
    @CsvSource({
            "COMPANY, 1",
            "PROJECT, 1204",
            "DEPOT,   3792",
            "BRANCH,  7",
    })
    @DisplayName("what the canonical writer encodes, the reader's codec decodes back (no grant is lost)")
    void writerOutputIsReadable(String kindName, String refId) {
        UUID userId = UUID.fromString("6f49871e-aaaa-bbbb-cccc-000000000001");
        DataAccessScope.ScopeKind kind = DataAccessScope.ScopeKind.valueOf(kindName);

        DataAccessScopeTupleEncoder.FgaTuple tuple = DataAccessScopeTupleEncoder.encode(scope(kind, refId, userId));

        Optional<ScopeObjectIdCodec.DecodedObjectId> decoded =
                ScopeObjectIdCodec.decode(tuple.objectType(), tuple.objectId());

        assertTrue(decoded.isPresent(),
                "reader must decode what the canonical writer produced: "
                        + tuple.objectType() + ":" + tuple.objectId());
        assertEquals(Long.parseLong(refId), decoded.get().id(),
                "entity id must survive writer → reader");
        assertFalse(decoded.get().legacyNumeric(),
                "canonical writer output must not be classified as legacy");
    }

    @Test
    @DisplayName("encoder and codec agree on the exact ADR-0008 object ids (no silent prefix drift)")
    void encoderAndCodecProduceIdenticalIds() {
        UUID userId = UUID.randomUUID();

        assertEquals(ScopeObjectIdCodec.encode("company", 1L),
                DataAccessScopeTupleEncoder.encode(scope(DataAccessScope.ScopeKind.COMPANY, "1", userId)).objectId());
        assertEquals(ScopeObjectIdCodec.encode("project", 1204L),
                DataAccessScopeTupleEncoder.encode(scope(DataAccessScope.ScopeKind.PROJECT, "1204", userId)).objectId());
        assertEquals(ScopeObjectIdCodec.encode("warehouse", 3792L),
                DataAccessScopeTupleEncoder.encode(scope(DataAccessScope.ScopeKind.DEPOT, "3792", userId)).objectId());
        assertEquals(ScopeObjectIdCodec.encode("branch", 7L),
                DataAccessScopeTupleEncoder.encode(scope(DataAccessScope.ScopeKind.BRANCH, "7", userId)).objectId());
    }

    @Test
    @DisplayName("PG depot → OpenFGA warehouse type, but the id prefix stays 'wc-department-'")
    void depotWarehouseAsymmetrySurvivesRoundTrip() {
        DataAccessScopeTupleEncoder.FgaTuple t =
                DataAccessScopeTupleEncoder.encode(scope(DataAccessScope.ScopeKind.DEPOT, "3792", UUID.randomUUID()));

        assertEquals("warehouse", t.objectType(), "ADR-0008 § Naming: PG 'depot' → OpenFGA 'warehouse'");
        assertEquals("wc-department-3792", t.objectId(), "id prefix follows the source table (DEPARTMENT)");
        assertEquals(3792L, ScopeObjectIdCodec.decode(t.objectType(), t.objectId()).orElseThrow().id());
    }

    @Test
    @DisplayName("regression witness: the OLD reader (bare Long.parseLong) could not read this id")
    void oldBareParseLongCouldNotReadCanonicalId() {
        // Documents the actual defect: listObjectIds() strips the "<type>:" prefix and then did
        // Long.parseLong(id). For canonical ids that throws NumberFormatException, which the old
        // code swallowed ("Non-numeric object ID skipped") — the grant disappeared silently.
        DataAccessScopeTupleEncoder.FgaTuple t =
                DataAccessScopeTupleEncoder.encode(
                        scope(DataAccessScope.ScopeKind.PROJECT, "1204", UUID.randomUUID()));

        org.junit.jupiter.api.Assertions.assertThrows(NumberFormatException.class,
                () -> Long.parseLong(t.objectId()),
                "if this ever parses, the writer stopped emitting ADR-0008 canonical ids");

        // ...and the codec (new reader) reads exactly the same value fine.
        assertEquals(1204L, ScopeObjectIdCodec.decode(t.objectType(), t.objectId()).orElseThrow().id());
    }

    @Test
    @DisplayName("writer subject is the KC sub — the reader must be given it as an alias (#2531)")
    void writerSubjectIsTheKeycloakSub() {
        UUID kcSub = UUID.fromString("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4");
        DataAccessScopeTupleEncoder.FgaTuple t =
                DataAccessScopeTupleEncoder.encode(scope(DataAccessScope.ScopeKind.PROJECT, "1204", kcSub));

        assertEquals(kcSub.toString(), t.userId(),
                "canonical grants are stored under the KC sub, NOT the numeric DB id — reading with the "
                        + "numeric id alone loses them, which is why /authz/me passes jwt.getSubject() "
                        + "as an alias subject");
        assertEquals("viewer", t.relation());
    }
}
