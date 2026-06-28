package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Faz 22.6 #1580 (ADR-0044 D5; Codex 019f078a) — the machine-provable negative proofs the recording-OFF VIEW_ONLY
 * data plane rests on:
 *
 * <ul>
 *   <li><b>No content persistence.</b> {@link LiveOnlyViewDataPlaneHandler} has no field or constructor parameter
 *       through which a screen byte could reach a durable / WORM / recording store. A future change that gives the
 *       recording-off handler a durable sink dependency fails this test (and the build).</li>
 *   <li><b>No payload in audit.</b> {@link ViewOnlyMetadataAuditSink} carries no parameter that could leak frame
 *       content ({@code byte[]} / {@code ByteString} / {@code ViewOnlyFrame} / a proto frame).</li>
 *   <li><b>No control coupling.</b> The fanout types do not depend on any control-plane type — a viewer can only
 *       receive, never reach back to the agent.</li>
 * </ul>
 */
class NoContentPersistenceGuardTest {

    private static final String[] DURABLE_TYPES = {
            "com.example.endpointadmin.remoteaccess.bridge.DurableRemoteBridgeAuditSink",
            "com.example.endpointadmin.remoteaccess.SessionRecorder",
            "com.example.endpointadmin.remoteaccess.DbRecordingSink",
            "com.example.endpointadmin.remoteaccess.RecordingSink",
            "com.example.endpointadmin.remoteaccess.RecordingAnchorSigner",
            "com.example.endpointadmin.remoteaccess.bridge.server.DurableRecordingDataPlaneHandler"
    };

    private static final String[] CONTROL_TYPES = {
            "com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry",
            "com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamHandle",
            "com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit"
    };

    private static List<Class<?>> resolve(String[] fqns) {
        List<Class<?>> classes = new ArrayList<>();
        for (String fqn : fqns) {
            try {
                classes.add(Class.forName(fqn));
            } catch (ClassNotFoundException ignored) {
                // a forbidden type that no longer exists cannot be depended on — skip it
            }
        }
        return classes;
    }

    @Test
    void liveOnlyHandler_hasNoDurableOrRecordingDependency() {
        List<Class<?>> forbidden = resolve(DURABLE_TYPES);
        assertFalse(forbidden.isEmpty(), "expected at least one durable type to resolve (test wiring sanity)");

        for (Field field : LiveOnlyViewDataPlaneHandler.class.getDeclaredFields()) {
            assertNotAssignable(forbidden, field.getType(),
                    "LiveOnlyViewDataPlaneHandler field '" + field.getName() + "'");
        }
        for (Constructor<?> ctor : LiveOnlyViewDataPlaneHandler.class.getDeclaredConstructors()) {
            for (Class<?> param : ctor.getParameterTypes()) {
                assertNotAssignable(forbidden, param, "LiveOnlyViewDataPlaneHandler constructor parameter");
            }
        }
    }

    @Test
    void metadataAuditSink_carriesNoPayload() {
        Class<?>[] forbiddenParamTypes = {byte[].class, ByteString.class, ViewOnlyFrame.class};
        for (Method method : ViewOnlyMetadataAuditSink.class.getDeclaredMethods()) {
            for (Class<?> param : method.getParameterTypes()) {
                for (Class<?> forbidden : forbiddenParamTypes) {
                    assertFalse(forbidden.isAssignableFrom(param),
                            "ViewOnlyMetadataAuditSink." + method.getName() + " must not accept " + forbidden);
                }
                // also reject any proto frame leaking through (name-based — proto package)
                assertFalse(param.getName().startsWith("com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame"),
                        "ViewOnlyMetadataAuditSink." + method.getName() + " must not accept a proto DataFrame");
            }
        }
    }

    @Test
    void fanoutTypes_haveNoControlPlaneCoupling() {
        List<Class<?>> control = resolve(CONTROL_TYPES);
        assertTrue(control.size() >= 2, "expected control-plane types to resolve (test wiring sanity)");

        List<Class<?>> fanoutTypes = List.of(
                LiveOnlyViewDataPlaneHandler.class,
                ViewOnlyViewerRegistry.class,
                ViewOnlyViewerSubscription.class,
                ViewOnlyStreamAuthorizationRegistry.class);

        for (Class<?> type : fanoutTypes) {
            for (Field field : type.getDeclaredFields()) {
                assertNotAssignable(control, field.getType(),
                        type.getSimpleName() + " field '" + field.getName() + "' couples to a control-plane type");
            }
        }
    }

    private static void assertNotAssignable(List<Class<?>> forbidden, Class<?> actual, String where) {
        for (Class<?> f : forbidden) {
            if (f.isAssignableFrom(actual)) {
                fail(where + " must not be a " + f.getName());
            }
        }
    }
}
