package com.example.permission.event;

import com.example.permission.service.TupleSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TB-15: Verifies @TransactionalEventListener AFTER_COMMIT behavior.
 * CNS-20260411-005: Codex flagged missing test for after-commit dispatch.
 */
@ExtendWith(MockitoExtension.class)
class RoleChangeEventHandlerTest {

    @Mock
    private TupleSyncService tupleSyncService;

    @InjectMocks
    private RoleChangeEventHandler handler;

    @Test
    @DisplayName("onRoleChange calls propagateRoleChange with correct roleId")
    void onRoleChange_callsPropagateWithRoleId() {
        handler.onRoleChange(new RoleChangeEvent(42L));

        verify(tupleSyncService).propagateRoleChange(42L);
        verifyNoMoreInteractions(tupleSyncService);
    }

    @Test
    @DisplayName("onRoleChange swallows exceptions without rethrowing")
    void onRoleChange_swallowsException() {
        doThrow(new RuntimeException("OpenFGA down")).when(tupleSyncService).propagateRoleChange(99L);

        // Should not throw — handler logs and swallows
        assertDoesNotThrow(() -> handler.onRoleChange(new RoleChangeEvent(99L)));
    }

    @Test
    @DisplayName("onRoleChange method has @TransactionalEventListener(AFTER_COMMIT)")
    void onRoleChange_hasAfterCommitAnnotation() throws Exception {
        Method method = RoleChangeEventHandler.class.getMethod("onRoleChange", RoleChangeEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
        assertNotNull(annotation, "@TransactionalEventListener annotation missing");
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase(),
                "Event handler must run AFTER_COMMIT to avoid stale state reads");
    }

    @Test
    @DisplayName("onRoleChange method has @Async annotation")
    void onRoleChange_hasAsyncAnnotation() throws Exception {
        Method method = RoleChangeEventHandler.class.getMethod("onRoleChange", RoleChangeEvent.class);

        assertTrue(method.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class),
                "@Async annotation missing — handler must run in separate thread");
    }

    @Test
    @DisplayName("RoleChangeEvent is a record with roleId")
    void roleChangeEvent_isRecord() {
        var event = new RoleChangeEvent(7L);
        assertEquals(7L, event.roleId());
    }
}
