package com.example.gpcore.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.port.RelationshipChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production adapter must INVERT the shared service's dangerous
 * disabled-allow-all default: a disabled OpenFGA store DENIES, unless an explicit
 * dev-bypass is set (Codex 019f1913 #2).
 */
class OpenFgaRelationshipCheckerTest {

    private final Principal alice = Principal.of("alice");
    private final NodeRef node = NodeRef.of("control_instance", "1");

    private OpenFgaAuthzService disabledService() {
        OpenFgaProperties props = new OpenFgaProperties();
        props.setEnabled(false); // disabled store
        return OpenFgaConfig.createAuthzService(props);
    }

    @Test
    void disabledStore_failsClosed_denies() {
        RelationshipChecker checker = new OpenFgaRelationshipChecker(disabledService(), false);
        assertFalse(checker.canRelate(alice, "viewer", node),
                "disabled OpenFGA must DENY in gp-core (not inherit dev allow-all)");
    }

    @Test
    void disabledStore_devBypass_allows() {
        RelationshipChecker checker = new OpenFgaRelationshipChecker(disabledService(), true);
        assertTrue(checker.canRelate(alice, "viewer", node), "explicit dev bypass allows");
    }

    @Test
    void disabledStore_batch_failsClosedAligned() {
        RelationshipChecker checker = new OpenFgaRelationshipChecker(disabledService(), false);
        List<Boolean> out = checker.canRelateBatch(alice, List.of(
                new RelationshipChecker.RelationRequest("viewer", node),
                new RelationshipChecker.RelationRequest("editor", node)));
        assertEquals(2, out.size());
        assertFalse(out.get(0));
        assertFalse(out.get(1));
    }
}
