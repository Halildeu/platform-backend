package com.example.commonauth.openfga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * board #2531 — ADR-0008 § "Object id encoding" round-trip contract.
 *
 * <p>Regression anchor: before this codec, {@code OpenFgaAuthzService.listObjectIds} did a bare
 * {@code Long.parseLong} and silently dropped every canonical slug, so a granted scope
 * (201 Created) never appeared in {@code /authz/me.allowedScopes} and the user still got 403.
 */
class ScopeObjectIdCodecTest {

    @ParameterizedTest(name = "ADR-0008 canonical: {0}/{1} -> {2}")
    @CsvSource({
            // objectType, entityId, canonical object id (ADR-0008 encoding table)
            "company,   1,    wc-our-company-1",
            "project,   1204, wc-project-1204",
            "warehouse, 3792, wc-department-3792",
            "branch,    7,    wc-branch-7",
    })
    @DisplayName("encode matches the ADR-0008 encoding table (writer parity)")
    void encodeMatchesAdr0008(String objectType, long id, String expected) {
        assertEquals(expected, ScopeObjectIdCodec.encode(objectType, id));
    }

    @ParameterizedTest(name = "decode canonical: {0}/{1} -> {2}")
    @CsvSource({
            "company,   wc-our-company-1,    1",
            "project,   wc-project-1204,     1204",
            "warehouse, wc-department-3792,  3792",
            "branch,    wc-branch-7,         7",
    })
    @DisplayName("canonical slug decodes and is NOT flagged legacy")
    void decodesCanonicalSlug(String objectType, String raw, long expected) {
        Optional<ScopeObjectIdCodec.DecodedObjectId> d = ScopeObjectIdCodec.decode(objectType, raw);
        assertTrue(d.isPresent(), "canonical slug must decode: " + raw);
        assertEquals(expected, d.get().id());
        assertFalse(d.get().legacyNumeric(), "canonical slug must not be flagged legacy");
    }

    @Test
    @DisplayName("round-trip: encode -> decode preserves the entity id for every scope type")
    void roundTrip() {
        for (String type : new String[] {"company", "project", "warehouse", "branch"}) {
            String encoded = ScopeObjectIdCodec.encode(type, 1204L);
            Optional<ScopeObjectIdCodec.DecodedObjectId> d = ScopeObjectIdCodec.decode(type, encoded);
            assertTrue(d.isPresent(), type + ": round-trip must decode");
            assertEquals(1204L, d.get().id(), type + ": id must survive round-trip");
            assertFalse(d.get().legacyNumeric(), type + ": round-trip is canonical");
        }
    }

    @Test
    @DisplayName("legacy bare numeric stays readable but is flagged (TupleSyncService tuples are live)")
    void legacyNumericIsAcceptedAndFlagged() {
        Optional<ScopeObjectIdCodec.DecodedObjectId> d = ScopeObjectIdCodec.decode("project", "1204");
        assertTrue(d.isPresent(), "legacy numeric must stay readable — failing closed would revoke live access");
        assertEquals(1204L, d.get().id());
        assertTrue(d.get().legacyNumeric(), "legacy form must be flagged for migration visibility");
    }

    @ParameterizedTest(name = "undecodable: {0}/[{1}]")
    @CsvSource({
            // another type's prefix must not be guessed at
            "project,   wc-our-company-1",
            "company,   wc-project-1204",
            // malformed tail / unknown prefix
            "project,   wc-project-",
            "project,   wc-project-abc",
            "project,   project-1204",
            "project,   -1204",
            "project,   1204x",
            "project,   ' '",
    })
    @DisplayName("unknown shapes are not guessed — decode returns empty (caller stays fail-closed)")
    void undecodableShapes(String objectType, String raw) {
        assertTrue(ScopeObjectIdCodec.decode(objectType, raw).isEmpty(),
                "must not guess an id from: " + raw);
    }

    @Test
    @DisplayName("null and empty ids decode to empty")
    void nullAndEmpty() {
        assertTrue(ScopeObjectIdCodec.decode("project", null).isEmpty());
        assertTrue(ScopeObjectIdCodec.decode("project", "").isEmpty());
    }

    @Test
    @DisplayName("SECURITY: Unicode digits must NOT decode into a real entity id (authz widening)")
    void unicodeDigitsAreNotIds() {
        // Character.isDigit() is true for these AND Long.parseLong converts them:
        //   Long.parseLong("١٢٠٤")   == 1204   (Arabic-Indic)
        //   Long.parseLong("１２０４") == 1204   (full-width)
        // The canonical writer can never emit them, so decoding them into project 1204 would grant
        // access through the decoder itself.
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project-١٢٠٤").isEmpty(),
                "Arabic-Indic digits must not decode");
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project-１２０４").isEmpty(),
                "full-width digits must not decode");
        assertTrue(ScopeObjectIdCodec.decode("project", "١٢٠٤").isEmpty(),
                "Unicode digits must not decode via the legacy numeric path either");
    }

    @Test
    @DisplayName("SECURITY: whitespace is not trimmed — a padded id is not the canonical id")
    void whitespaceIsNotNormalised() {
        assertTrue(ScopeObjectIdCodec.decode("project", " wc-project-1204 ").isEmpty());
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project-1204\n").isEmpty());
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project-1204\t").isEmpty());
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project- 1204").isEmpty());
        assertTrue(ScopeObjectIdCodec.decode("project", " 1204").isEmpty(),
                "legacy numeric path must not trim either");
    }

    @Test
    @DisplayName("leading zeros are non-canonical (both live writers emit minimal decimal)")
    void leadingZerosRejected() {
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project-001204").isEmpty());
        assertTrue(ScopeObjectIdCodec.decode("project", "01204").isEmpty());
        // but a bare zero is still a valid minimal decimal
        assertEquals(0L, ScopeObjectIdCodec.decode("project", "wc-project-0").orElseThrow().id());
    }

    @Test
    @DisplayName("plus/minus signs and overflow do not decode")
    void signsAndOverflow() {
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project-+1204").isEmpty());
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project--1204").isEmpty());
        assertTrue(ScopeObjectIdCodec.decode("project", "wc-project-99999999999999999999").isEmpty(),
                "overflow must not silently wrap");
    }

    @Test
    @DisplayName("supports() only covers ADR-0008 scope object types")
    void supportsOnlyScopeTypes() {
        assertTrue(ScopeObjectIdCodec.supports("project"));
        assertTrue(ScopeObjectIdCodec.supports("PROJECT"), "type matching is case-insensitive");
        assertFalse(ScopeObjectIdCodec.supports("module"), "module is not a scope object type");
        assertFalse(ScopeObjectIdCodec.supports("organization"));
        assertFalse(ScopeObjectIdCodec.supports(null));
    }

    @Test
    @DisplayName("encode refuses object types with no ADR-0008 encoding")
    void encodeRefusesUnknownType() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScopeObjectIdCodec.encode("module", 1L));
        assertTrue(e.getMessage().contains("module"));
    }

    @Test
    @DisplayName("depot->warehouse asymmetry preserved (type=warehouse, prefix=wc-department-)")
    void depotWarehouseAsymmetry() {
        assertEquals("wc-department-3792", ScopeObjectIdCodec.encode("warehouse", 3792L));
        assertTrue(ScopeObjectIdCodec.decode("warehouse", "wc-warehouse-3792").isEmpty(),
                "the type name is NOT the id prefix (ADR-0008: source table = DEPARTMENT)");
    }
}
